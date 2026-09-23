# M4 — Job Worker, Operations & Catalogue Authoring

> Asynchronous grading, operational visibility, and the ability to author the
> content M3 consumes. Still headless: no HTTP, no authentication, no frontend,
> no real AI provider.
>
> Verified 2026-09-23 against PostgreSQL: `mvn clean verify` → **259/259 tests**.

## 1. Scope

| Part | Delivered |
|---|---|
| Job worker | Database-backed queue, atomic claiming, bounded retry with backoff, lease recovery, graceful shutdown |
| Operations | Queue read model, AI-usage read model, cross-module overview, queue health indicator, lifecycle logging |
| Catalogue authoring | Question, rubric and template authoring with publication validation and enforced immutability |

**Not in M4:** any real model provider, voice, ranking, analytics, or an admin
UI. The deterministic provider remains the one used throughout.

## 2. Database changes

**NONE.** No migration was added or edited; no table, column, index, constraint
or trigger changed. `ddl-auto=validate` passing confirms zero drift.

The `jobs` table has existed since M1 and was unused until now. Every field
Part 1 needs was already there:

| Requirement | Existing column / index |
|---|---|
| pending work | `status='QUEUED'`, `run_after`, partial index `ix_jobs_claim` |
| ownership / claim | `locked_by`, `locked_at`, and `ck_jobs_lock` — a RUNNING row *must* name its owner |
| retry count | `attempts`, `max_attempts`, `ck_jobs_att` |
| next attempt time | `run_after` |
| completion / failure | `SUCCEEDED` / `FAILED` / `DEAD`, `last_error` |
| stale recovery | `locked_at`, partial index `ix_jobs_stale` |
| duplicate delivery | `uq_jobs_active_dedupe`, partial over `('QUEUED','RUNNING')` |

Catalogue authoring needed nothing either: `status` DRAFT/PUBLISHED/ARCHIVED
plus the publish-gate and immutability triggers already express the whole
draft-versus-published model.

One additive code change: `ErrorCode.CONFLICT`, a generic 409 that was missing.
Adding an enum value is backwards compatible by that type's own contract.

## 3. Worker architecture

```
answer written ──tx──> job enqueued        (same transaction)
                          │
              tx: claim ──┘                (FOR UPDATE SKIP LOCKED)
                          │
              (no transaction) handler runs ── provider call
                          │
              tx: settle outcome
```

```
shared/jobs/
├── api/            JobQueue, JobHandler, JobExecutionException,
│                   JobOperations, JobType, JobStatus
├── application/    DefaultJobQueue, JobClaimService, JobWorker,
│                   JobWorkerScheduler, DefaultJobOperations
├── domain/         JobEntity
└── infrastructure/ JobRepository, JobQueueHealthIndicator
```

**`shared` does not know what an evaluation is.** It defines `JobHandler`;
business modules implement it. `EvaluateAnswerJobHandler` lives in the interview
module, so the dependency points inward and `shared → interview` never appears —
the existing ArchUnit rule would fail the build if it did.

The handler delegates to `InterviewService.evaluateAnswer`, which M3 already
uses. The job decides *when* grading happens, not *what* happens, so the
synchronous and asynchronous paths cannot drift apart.

## 4. Job lifecycle

```
QUEUED ──claim──> RUNNING ──success──> SUCCEEDED
   ▲                 │
   │                 ├──retryable──> QUEUED (run_after = now + backoff)
   │                 └──permanent──> DEAD
   └──lease expiry───┘
```

Job state is deliberately **separate from evaluation state**. A job says whether
the work ran; whether the evaluation succeeded is the evaluation module's
record. Conflating them would let a retried job rewrite a candidate's result,
and would leave a permanently dead job with nowhere to say "this was never
graded".

`FAILED` is the transient state a handler reports, not somewhere work rests: a
retry returns the row to `QUEUED`, because the claim query looks only at queued
rows and a job parked anywhere else would never run again.

## 5. Claiming and concurrency

```sql
SELECT * FROM app.jobs
 WHERE status = 'QUEUED' AND run_after <= :now
 ORDER BY priority, run_after
 LIMIT :batchSize
 FOR UPDATE SKIP LOCKED
```

Native SQL because `FOR UPDATE SKIP LOCKED` has no JPQL equivalent — and
`SKIP LOCKED` is the whole point. Without it a second worker would *block* on
the first's rows instead of moving past them, turning parallel workers into a
queue of one.

**Two workers cannot claim the same row.** The first holds a row-level write
lock for the rest of its short claim transaction; the second never sees the row;
and by the time the lock releases the row is no longer `QUEUED`, so it fails the
predicate anyway. No application-level check-then-set is involved, which is what
makes this correct rather than merely unlikely to collide.

Proven by test: two workers started behind a latch, racing for one job, produce
exactly one handler invocation.

## 6. Retries and backoff

Handlers classify their own failures, because only the handler knows whether a
provider timed out or the request referenced something that no longer exists:

| Kind | Examples | Outcome |
|---|---|---|
| Retryable | timeout, rate limit, dropped connection, exhausted pool | back to `QUEUED` with backoff |
| Permanent | malformed payload, missing pinned version, impossible domain state | `DEAD` immediately, attempts unspent |

An **unrecognised** exception is treated as retryable. An unexpected fault is
more often transient than permanent, and burying work on a surprise loses a
candidate's grade for good; attempts are bounded, so a genuinely broken job
still dies.

Backoff doubles from a configured start and is capped: 5s, 10s, 20s, 40s by
default, ceiling 600s. Bounded at both ends deliberately — no immediate retry,
so a failing provider is not hammered, and a ceiling so a long-lived queue does
not schedule work days out.

All values are in `AppProperties.Jobs` and externalised:

| Property | Default | Why |
|---|---|---|
| `app.jobs.worker-enabled` | true | Lets the worker be split into its own deployment by configuration alone |
| `app.jobs.poll-interval-ms` | 1000 | Nobody waits on grading; one indexed query per second is uninteresting |
| `app.jobs.batch-size` | 5 | One worker cannot take the whole backlog and lose it by crashing |
| `app.jobs.lease-seconds` | 300 | An order of magnitude above the 30-second provider timeout |
| `app.jobs.max-attempts` | 4 | |
| `app.jobs.initial-backoff-seconds` | 5 | |
| `app.jobs.max-backoff-seconds` | 600 | |
| `app.jobs.backlog-warning-threshold` | 500 | Advisory only |

## 7. Stale work recovery

A worker can die after claiming. Recovery is two single UPDATE statements, so it
is itself atomic — two instances running it together cannot revive the same row:

1. `RUNNING` rows whose `locked_at` predates the lease return to `QUEUED`, with
   the previous owner recorded in `last_error`.
2. `QUEUED` rows already at `max_attempts` are buried.

The second step matters: without it a job that reliably kills its worker would
cycle for ever.

**The attempt is counted at claim time, not at completion.** A crash still
consumes an attempt, which is what makes that bounding real.

Shutdown and crash are deliberately the same path: `stop()` halts claiming and
lets in-flight work finish, and anything that does not finish keeps its lease
and is reclaimed. Interrupting a handler between its provider call and its write
is precisely how a job ends up permanently stuck.

## 8. Transaction boundaries

`JobWorker` holds **no transaction** while a handler runs. Every write goes
through `JobClaimService`, whose methods are `REQUIRES_NEW` so a claim is never
entangled with anything else:

```
tx: claim (+ increment attempt)   commit
    handler runs — no transaction, no lock held
tx: mark succeeded / retry / dead  commit
```

This preserves the M2 rule exactly. A ten-second model call inside a transaction
would pin a connection and a row lock for its duration.

## 9. Idempotency

No new mechanism. Each guarantee rests on a constraint that already exists:

| Risk | Guaranteed by |
|---|---|
| Duplicate enqueue | `uq_jobs_active_dedupe` — partial, so work can be re-enqueued *after* it finishes, which is what makes an administrative re-run possible |
| Duplicate execution | The handler's own idempotency: M2 will not re-call a provider for an answer that already has a current evaluation |
| Two current evaluations | `uq_evaluations_one_current` (M2) |
| Answer lost on failure | The answer is written and committed before any job runs |

The job row is enqueued **in the same transaction as the answer**. Either both
commit or neither does, so there is no window in which a stored answer has
nothing scheduled to grade it, and no outbox to drift.

## 10. Operational visibility

Every figure is counted from stored rows. Nothing is sampled or accumulated in
memory — a restart must not change the answer, and a number an operator cannot
reproduce with SQL is one they will not trust at three in the morning.

| Question | Where |
|---|---|
| How much work is pending / running / dead? | `JobOperations.snapshot()` |
| How old is the backlog? | `oldestQueuedAt`, `oldestQueuedAgeSeconds` |
| How many retries has the system done? | `totalAttempts` |
| What is failing repeatedly? | `retryingJobs()` — attempts and last error |
| What will never run without help? | `deadJobs()` |
| Which provider is failing? | `AiUsageReadModel.usageSince()` — the existing `ai_invocations` table, not a second counter |
| All of it at once | `admin.api.OperationsOverview` |

The admin module composes these through published contracts and owns no tables,
so it cannot become a second source of truth.

**Health.** `JobQueueHealthIndicator` reports `DOWN` only when the queue cannot
be read — which means the database is unreachable and nothing works anyway. A
deep backlog or dead work is reported as `UP` **with detail**: a failed
evaluation is a work-item failure, and reporting the instance unhealthy for it
would turn one broken answer into an outage for everyone.

**Logging.** Lifecycle events are logged with ids and safe metadata: job
claimed, started, succeeded, retry scheduled, permanently failed, lease
reclaimed, catalogue published, publish rejected. Job payloads carry correlation
ids only — never answer text — so the payload is safe to store and display.

## 11. Catalogue authoring

```
Draft ──edit──> Draft ──validate + publish──> Published (immutable)
                                                   │
                                          createNextVersion
                                                   ▼
                                                 Draft v2
```

```
question/api/
├── QuestionCatalog    (M3, consumer)      QuestionAuthoring   (M4, author)
└── TemplateCatalog    (M3, consumer)      TemplateAuthoring   (M4, author)
```

Read and write contracts are deliberately separate. The interview engine
consumes catalogue content and must never acquire the ability to create or
publish any — keeping the operations apart means a reader cannot accidentally
become a writer.

Whole-set replacement is used for rubrics, template skills and template slots
rather than per-row CRUD. Each of those has a cross-row invariant — weights
totalling 10000, contiguous slot positions — and no intermediate state satisfies
it. Validating a complete set once is both simpler and safer than validating
after each of six row operations.

## 12. Publication validation

Publishing fails with **every** problem at once, not the first: an author fixing
one issue only to be told about the next wastes a round trip each time.

**Question** cannot publish without prompt text, a reference answer, a skill,
3–10 rubric criteria, or with criterion weights that do not total 10000 bp.
Criteria additionally need a code, a non-duplicate code, and an expectation
stating an observable claim — a topic label is not gradable, because two graders
would read it differently.

**Template** cannot publish without skill weights totalling 10000 bp, a slot
count matching its declaration, slots whose skills the template weights, or
pinned slots whose question has a published version.

One check the database cannot make: **can the question bank actually satisfy the
plan?** A template that publishes but cannot be started is worse than one that
refuses, because the failure surfaces to a candidate instead of to its author. A
thin pool (enough to fill the plan but without headroom) is logged as a warning
rather than refused — a small bank should still be usable, but every candidate
seeing an identical interview is worth knowing about.

Application validation gives useful domain errors; the database triggers remain
the authority and cannot be bypassed.

## 13. Immutability and versioning

A published question version and a published template are immutable. The
authoring services refuse an edit with an explanation naming the next version
number; `trg_qv_20_guard` and `trg_tpl_20_guard` reject it regardless. Both
matter — one is usability, the other is correctness.

`createNextVersion` copies content into a fresh draft and leaves the published
row completely untouched, including its rubric and, for templates, its skills
and slots. Every interview that pinned the old version keeps grading against
exactly the words its candidate saw.

Proven by test: publish v1, create v2, confirm v1 is still `PUBLISHED` with its
original content and a different version id.

## 14. Authorization boundary

**This is the honest position: authorization is not implemented, and M4 does not
pretend otherwise.**

There is no authenticated identity in the system yet — authentication is
deliberately deferred to a later milestone. What M4 establishes is the
*boundary*, not the enforcement:

- Authoring lives behind separate interfaces (`QuestionAuthoring`,
  `TemplateAuthoring`) from the consumer contracts the interview engine uses, so
  the two are distinguishable in code and will be distinguishable at a
  controller.
- Every authoring command carries an `authorId`, recorded as `created_by` and
  `published_by`. That is **provenance, not permission**: nothing verifies the
  caller is who they claim, because there is no authenticated caller to verify.
- No organizations, roles, permissions or tenancy tables were invented. The
  approved schema has none and speculating would be worse than waiting.

**Concretely: anything that can call these services can author and publish
content.** Today that is only tests, because there is no HTTP surface. Before a
controller is added, the authentication milestone must supply an authenticated
admin identity and a role check, and `authorId` must come from that identity
rather than from the caller.

## 15. Architecture decisions

1. **The existing `jobs` table was sufficient** — no migration. The proof is in
   §2: every required field already existed, with the right partial indexes.
2. **`shared` owns queue mechanics; business modules own handlers.** Dependency
   inversion, so the queue never learns what an evaluation is.
3. **The handler delegates to `InterviewService`** rather than reimplementing
   orchestration, so async and sync grading cannot diverge.
4. **A provider failure does not fail the job.** M2 records it as
   `FAILED_PROVIDER` and returns normally, so the job succeeds while the
   evaluation is marked failed. Retrying at the job level would double-retry
   what the evaluation module already handled.
5. **Worker concurrency comes from more instances, not more threads.** A thread
   pool inside one process loses its whole batch when that process dies; the
   claim mechanism already makes multiple instances safe.
6. **`worker-enabled` gates the scheduler, not the worker.** Gating both would
   allow a worker that is willing but unreachable.

## 16. Test results

259 tests, all passing. M4 added 47.

| Suite | Tests | Covers |
|---|---|---|
| `JobWorkerIntegrationTest` | 22 | No work, success, retryable and permanent failure, unexpected failure, max attempts, backoff growth, unhandled type, duplicate enqueue, re-enqueue after completion, duplicate delivery, stale claim recovery, live claim untouched, exhausted reclaim, two-worker race, batch sharing, shutdown, worker identity, operational read model |
| `CatalogueAuthoringIntegrationTest` | 25 | Question create/edit/publish/immutability/versioning, rubric validity and weights and ordering and duplicates, template create/slots/reorder/validation/publish/immutability/versioning, and the full authored-to-interview flow |

Regression: all M0–M3 suites unchanged and green.

Two fixtures had to change for real reasons, both worth noting:

- **`MigrationAndMappingTest.seedSkillsApplied` was asserting the whole
  `skills` table.** It passed only because of incidental test ordering; once M4
  suites added skills of their own it broke. Now scoped to the seeded codes,
  which is what the test was ever about.
- **The worker suite truncates the queue before each test.** The queue is
  global and a worker claims whatever is available, so one test's leftovers
  became another's phantom work.

## 17. Deferred

1. **No real AI provider.** The deterministic provider is still the only one.
2. **No HTTP surface.** Authoring and operations are service-level only.
3. **No authorization** — see §14.
4. **`GENERATE_REPORT` has no handler.** The job type exists; M5 supplies it.
5. **No expiry sweeper job.** `MAINTENANCE` exists as a type and
   `InterviewRepository.findExpired` is written, but nothing schedules it;
   expiry is still enforced when an attempt is next touched.
6. **No retention job.** `ai_invocations.raw_response` is documented as purged
   after 30 days; nothing purges it yet.
7. **No metrics export.** Operational data is available through a read model,
   not Micrometer.
8. **Dead jobs cannot be re-driven through an API.** An operator can see them;
   re-running one requires a database update until an admin surface exists.
