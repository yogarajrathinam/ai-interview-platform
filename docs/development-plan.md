# Development Plan

> Supersedes `09-development-plan.md`. Milestone numbering is unchanged;
> content and scope reflect the approved review
> ([10-architecture-review.md](10-architecture-review.md)).
>
> Last updated: M5A complete (HTTP API and candidate UI vertical slice).

## Sequencing principles

1. **The riskiest thing is validated earliest.** The product bet is evaluation
   quality. Anything that grades a real answer against a real rubric sooner
   beats anything that makes the UI nicer.
2. **The schema is the highest-coupling artefact.** Getting it wrong costs a
   data migration over live results, so it comes first, with tests.
3. **Every milestone ends in something demonstrable.** No milestone is
   "refactoring week".

Deliberately not first: authentication UI, landing page, design polish. They are
necessary, they are not risky, and doing them first delays the only question
that matters.

## Status

| Milestone | Scope | Status |
|---|---|---|
| **M0** | Repository, module skeleton, cross-cutting foundation, ArchUnit, CI-ready build | ✅ **Done** |
| **M1** | Flyway baseline, 18 tables, triggers, JPA mapping, invariant tests | ✅ **Done** |
| **M2** | Headless evaluation engine, deterministic provider, backend-owned scoring | ✅ **Done** — see [m2-evaluation-engine.md](m2-evaluation-engine.md) |
| **M3** | Interview & question engine: lifecycle, sequencing, selection, follow-up graph | ✅ **Done** — see [m3-interview-engine.md](m3-interview-engine.md) |
| **M4** | Job worker, operations, catalogue authoring | ✅ **Done** — see [m4-worker-operations-catalogue.md](m4-worker-operations-catalogue.md) |
| **M4.5** | Real AI evaluation provider, versioned prompt, golden set and agreement gate | ✅ **Done** — see [m5-ai-provider-golden-set.md](m5-ai-provider-golden-set.md) |
| **M5A** | HTTP API and candidate UI vertical slice (no auth) | ✅ **Done** — see [m5a-http-api-candidate-ui.md](m5a-http-api-candidate-ui.md) |
| **M5** | Reporting: aggregation, bands, coverage | |
| **M6** | Auth end to end: JWKS verification, JIT provisioning, ownership checks | |
| **M7** | Design system and app shell | |
| **M8** | Candidate experience, including the runner | |
| **M9** | Admin experience | |
| **M10** | Content, hardening, launch | |

Critical path: M1 → M2 → M4 → M5 → M8.

> **A numbering note.** The working brief for the AI provider called it "M5",
> but this plan's M5 has always been Reporting. It is recorded above as **M4.5**
> so that M5–M10 keep the numbers everything else already refers to — the
> document it delivers is still named `m5-…` because that is what the brief
> called it. Renumbering the roadmap is a call for the product owner, not a
> side effect of a build; say the word and M5–M10 shift down by one.

## M2 — Evaluation engine, headless ✅ *(done)*

Delivered as the headless engine with a **deterministic provider**: the whole
pipeline — contract, validation gates, evidence location, backend-owned scoring,
lifecycle, idempotency, accounting, reproducibility — proven end to end without
a model. See [m2-evaluation-engine.md](m2-evaluation-engine.md).

**Split out of M2, not dropped.** The real provider and the golden set move to a
dedicated AI-integration milestone. The reasoning: those two items are the
*quality* bet, while everything else in M2 is the *plumbing* the quality bet
runs on. Proving the plumbing first means any disagreement measured later is
attributable to the prompt or the model, not to a scoring bug — and it removes
the provider account and budget from the critical path of everything downstream.
The stop-gate below moves with them and still governs.

The milestone ordering is otherwise unchanged.

### What the original plan scoped here, for reference

- `ai` module: `AiClient` port, `AiGateway` (timeout, bounded retry, budget cap,
  `ai_invocations` accounting), one real provider adapter + `MockAdapter`.
  **No circuit breaker, no provider failover** — the job queue already absorbs
  an outage.
- `evaluation` module: versioned prompt resource, `RubricEvaluator`,
  `ResponseValidator` (gates G1–G7, including the verbatim-evidence check),
  `ScoreCalculator`, persistence.
- A CLI/test entry point: given a question version and an answer, print the full
  evaluation and derived score.
- Golden set v0: 20 hand-labelled cases across 3 questions, plus the 5
  prompt-injection cases. **First measure human-to-human agreement on 10 cases**
  to establish the ceiling before judging the model against 80%.

**Demo:** grade a real HashMap answer end to end — criterion verdicts, quoted
evidence, derived score — and show a hallucinated-evidence case being downgraded.

**Stop-gate:** if criterion agreement is below ~70%, stop and fix the approach
before building anything on top of it. A pivot here costs days, not weeks.

## M3 — Interview & question engine ✅ *(done)*

Delivered per the approved sequencing decision: attempt lifecycle, question
sequencing and selection, turn orchestration, and the deferred follow-up graph.
See [m3-interview-engine.md](m3-interview-engine.md).

**No schema change was needed.** The M1 baseline already carried every state,
constraint and column the engine required — including
`follow_ups_selected_at`, which existed for exactly this purpose.

**Catalogue authoring moved to M4.** This slot originally held question and
template *authoring* services. M3 needed the catalogue only for *reading*
published content, which the extended `QuestionCatalog` and the new
`TemplateCatalog` now provide. Authoring is admin functionality with no consumer
until an admin surface exists, so building it now would have been speculative.

## M4 — Job worker, operations and catalogue authoring *(≈4 days)*

The DB-backed job worker that drives grading off the request thread, the expiry
and idle sweeper, and the authoring services deferred from M3 (publish flows
above the database gates, the seed command, `dry-run-evaluation`).

**Demo:** drive a whole interview end to end with grading running
asynchronously, including a duplicate submit and a provider outage that leaves
the interview usable.

## M4.5 — Real AI provider and golden set ✅ *(done)*

The deterministic provider from M2 is replaced, behind the unchanged
`EvaluationProvider` port, by a real model returning schema-constrained verdicts.
Nothing in `evaluation.api`, `evaluation.domain` or `evaluation.application`
changed, which was the test of whether the M2 port was genuine.

Added with it: a versioned prompt (`answer-evaluation-v1`), a hand-labelled
golden set, and a criterion-level agreement gate defaulting to 0.70. The gate is
opt-in and excluded from CI — it calls a paid, non-deterministic service, and a
build that goes red on model variance teaches people to ignore red. **CI proves
the pipeline and the metric; the live gate proves the grader.**

M10 still owns growing the set to 40 cases.

See [m5-ai-provider-golden-set.md](m5-ai-provider-golden-set.md).

## M5 — Reporting *(≈2 days)*

Skill aggregation, overall score with renormalisation over covered skills,
bands, coverage, strengths and gaps, optional AI summary with a deterministic
fallback. **The report never depends on an AI call succeeding.**

*The backend is now feature-complete and verifiable without a single screen.*

## M6–M9 — Auth, design system, candidate and admin experience *(≈14 days)*

Per the approved scope. The candidate screen inventory is fixed; anything not
listed is Phase 1.

## M10 — Content, hardening, launch *(≈5 days)*

- Question bank to **45 published questions across 3 skills** (Java, Spring
  Boot, SQL) and 2 templates. React, Angular, REST, Microservices and System
  Design are *data*, added post-launch with no deploy.
- Golden set to 40 cases; full harness run recorded.
- Rate limiting, security headers, CORS, retention job, the four alerts.
- **Backup restore rehearsal** — an untested backup is not a backup.
- One Playwright happy-path E2E; accessibility and responsive passes.

## Definition of done, every milestone

- Unit tests for logic, database tests for persistence, MockMvc for web.
- ArchUnit clean; no new boundary suppressions.
- Migrations forward-only and tested from an empty database.
- No secret, PII, or answer text in logs.
- New endpoints have an authorisation test including a wrong-owner case.
- Errors return problem details with a stable `code`.
- A query-count assertion on `/state` and `/result` once they exist.

## Known schedule risks

| Risk | Mitigation |
|---|---|
| **Question authoring under-delivers** — the top schedule risk | Cut to 45 questions; start during M3; track weekly; 45 is a launch criterion |
| Evaluation quality inadequate | M2 stop-gate, before anything is built on top |
| Golden-set labelling skipped under time pressure | It gates every future model change; treated as a launch blocker |
| Frontend scope creep in M8/M9 | The screen inventory is fixed |

## Environment dependencies to resolve

| Need | For | Status |
|---|---|---|
| Supabase projects (staging + prod) with **asymmetric JWT signing keys** | M6 | **Not yet provisioned** — do this during M2/M3 so M6 is not blocked |
| AI provider account, ~$100 development budget, zero-retention configuration | M2 | Not yet provisioned |
| A container runtime **or** a disposable PostgreSQL | CI and local tests | Workstation has no Docker and no admin rights; the test suite supports an external database for this reason |
| Human labelling capacity for the golden set | M2, M10 | Hardest resource to secure, easiest to under-plan |
