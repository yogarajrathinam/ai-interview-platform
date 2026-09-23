# M3 — Interview & Question Engine

> The attempt lifecycle, question sequencing and turn orchestration. Still
> headless: no HTTP, no authentication, no frontend, no real AI provider.
>
> Verified 2026-09-23 against PostgreSQL: `mvn clean verify` → **212/212 tests**.

## 1. What M3 owns

```
start -> plan materialised -> serve turn -> answer -> grade
      -> ... -> follow-up phase -> complete
```

M3 decides **what to ask next and when to stop**. It calls the evaluation
module through that module's public contract and reproduces none of its
scoring — no formulas, no verdicts, no rubric interpretation.

## 2. What was reused rather than built

The schema already carried almost everything. **No migration was added.**

| Needed | Already present |
|---|---|
| Attempt lifecycle | `interviews.status` (4 values) + `completion_reason` (7 values) |
| Turn lifecycle | `interview_questions.status` (6 values) |
| Follow-up graph | `ck_iq_shape` — parent, criterion, own prompt, zero weight |
| Version provenance | `interview_questions.question_version_id`, `ON DELETE RESTRICT` |
| One live attempt | `uq_interviews_one_live` |
| One answer per turn | `answers` primary key |
| Turn ordering | `uq_iq_position` |
| Follow-up phase marker | `interviews.follow_ups_selected_at` |
| Deadline / idle tracking | `hard_deadline_at`, `last_activity_at` |

**Two states from the brief were deliberately not introduced.**
`WAITING_FOR_ANSWER` and `EVALUATING` are both derivable from turn status, and
`NEXT_QUESTION` is an operation rather than a state. Storing them would create
a second source of truth able to disagree with the turn rows — and the whole
reason the runner survives a refresh is that nothing about position is stored
anywhere but those rows. `CREATED` stays absent for the M1 reason: creation and
start are one operation, so no observer could ever see the gap.

## 3. Architecture

```
interview/
├── api/            InterviewService, InterviewState, commands,
│                   InterviewStatus · TurnStatus · TurnKind · CompletionReason
├── application/    DefaultInterviewService   orchestration, no transaction
│                   InterviewPlanner          slots -> concrete turns
│                   QuestionSelector          which version fills a slot
│                   TurnSequencer             derives all client-visible state
│                   FollowUpSelector          deferred probe selection
│                   InterviewWriter           every database mutation
├── domain/         InterviewLifecycle        pure transition rules
│                   InterviewEntity · InterviewQuestionEntity · AnswerEntity
└── infrastructure/ three repositories
```

**Dependency direction, enforced:** `interview → evaluation.api` and
`interview → question.api`. The existing ArchUnit rule
`evaluation_does_not_depend_on_the_interview_module` guarantees it never runs
back, and the generic cross-module rule keeps both modules' internals private.

## 4. Deliberate API extensions

Two published contracts were insufficient and were extended rather than bypassed.

**`question.api.QuestionCatalog`** gained three planning methods, and the
distinction is drawn explicitly in the interface:

> The existing methods read a version an attempt has **already pinned**. The new
> ones resolve **which version to pin** in the first place. Nothing in the
> grading path may use the latter.

- `findPublishedVersionOfQuestion` — resolve a pinned slot at start
- `findPublishedVersionsForPool` — eligible versions for a pooled slot
- `findQuestionIdsForVersions` — map versions to families for exposure avoidance

**`question.api.TemplateCatalog`** is new: template configuration and slots,
addressed by template *version* id, with one explicit family-to-published
resolution used only at start.

**`evaluation.api.EvaluationReadModel`** is new. M2 noted that follow-up merging
needs the turn graph the interview module owns; the missing half was the
interview module knowing *which criterion* an answer covered weakly. Exposed as
a read model — it can report what grading concluded and nothing more.

## 5. Question selection

Two guarantees:

**Deterministic.** Randomness is seeded from the interview id, so plans vary
across candidates but replay identically for any one of them. Selection also
sorts on a derived key rather than shuffling, so it does not depend on the order
the catalogue happens to return rows in.

**No repeats.** A question family appears at most once per plan (a hard
constraint). A family the candidate met in an earlier attempt is avoided *where
the pool allows* (a preference). Exposure avoidance is deliberately not a
constraint: with a small bank, refusing to repeat would mean refusing to start
an interview, which is far worse than a familiar question.

**Planning is all-or-nothing.** A slot that cannot be filled fails the start.
A candidate who answers six questions when the template promised eight has a
score that means something different from everyone else's.

## 6. Provenance

Every turn pins `question_version_id` at start and never re-resolves. `skill_id`
and `weight_bp` are snapshotted onto the turn, so a score stays recomputable
from attempt-scoped rows alone. Grading passes that same pinned id to the
evaluation module, so the answer is graded against the rubric the candidate
actually saw — proven by an M2 test that archives v1, publishes a stricter v2,
and re-grades to the same score.

## 7. Follow-up graph

Follow-ups are **deferred**: selected after the core plan completes, never
interleaved. That is what keeps grading off the candidate's critical path —
nobody waits on a model for their next question, because every core question was
decided at start.

```
core turn -> answer -> evaluation -> weak criterion
                                        -> follow-up turn (parent_id,
                                           follow_up_criterion_id, own prompt,
                                           weight_bp = 0)
```

Selection ranks criteria by unearned weight (`weight × (1 − credit)`), so the
probe goes to what cost the most. Filters: verdict weak, confidence ≥ 0.6, a
**curated** follow-up prompt exists, and the answer was neither off-topic nor an
injection attempt.

Three properties worth stating:

- **The model cannot author what a candidate sees.** Only curated prompts are
  asked. A model that could summon extra questions could also be talked into it.
- **An off-topic or injection-suspected answer is never probed.** There is no
  weakness to explore in an answer to a different question, and re-engaging with
  an injection attempt is the one thing that could give it a second chance.
- **Unsettled grading skips the phase entirely.** Waiting would put a model call
  back on the critical path; a missed probe costs far less than a stalled
  interview.

M3 builds the graph; merging a probe's verdict back into its parent criterion
remains a later milestone, as M2 anticipated.

## 8. Idempotency and concurrency

No new mechanism. Every repeatable command rests on a constraint that already
exists.

| Command | Repeated behaviour | Guaranteed by |
|---|---|---|
| `start` | Resumes the live attempt | `uq_interviews_one_live` |
| `advance` | Returns the same turn, keeps the original ask time | lowest-open-turn rule |
| `submitAnswer` | Returns the stored answer, `duplicate = true` | `answers` primary key |
| `evaluateAnswer` | Does not re-call the provider | `uq_evaluations_one_current` (M2) |
| `complete` | Returns unchanged state | status guard |
| follow-up phase | Runs at most once | row lock + `follow_ups_selected_at` |

**The concurrency case that matters** — two requests asking for the next
question — cannot issue two different questions, because "next" is always the
lowest-position open turn. Both callers compute the same turn and converge.

Appending follow-ups is the one operation that is *not* naturally idempotent:
two concurrent advances could each decide to probe. That path takes a
`PESSIMISTIC_WRITE` lock on the attempt row; the second caller waits, then sees
the phase has already run. The `follow_ups_selected_at` marker is what
distinguishes "chose none" from "not yet looked".

## 9. Transaction boundaries

The M2 rule is preserved: **no provider call happens inside a transaction.**

`DefaultInterviewService` is deliberately not `@Transactional`. Every write
lives in `InterviewWriter` in a short transaction, and the grading call sits
between writes, never inside one:

```
TX: store answer, mark ANSWERED
    (no TX) evaluation module calls the provider
TX: mark EVALUATED or EVAL_FAILED
```

The answer is durable before anything grades it, so no provider failure can lose
the candidate's work — and a failed grading leaves the answer intact and
re-drivable, with no verdict manufactured.

## 10. Completion

`complete` moves the attempt to `COMPLETING`, and it closes only once every turn
has settled. An attempt that closed while an answer was still being graded would
produce a report missing a question the candidate did answer.

**Finishing early records unreached questions as skipped.** They score zero and
are counted: excluding them would inflate the score of someone who simply
stopped, and leaving them open would strand the attempt in `COMPLETING` forever.

`finalizeCompletion` is where M5 will generate the report, between `COMPLETING`
and `COMPLETED`. Today it performs the transition and nothing else, so a
completed attempt currently has no report — which is exactly the documented
`NOT_SCORED` representation until M5 fills it in.

## 11. Test strategy

212 tests, all passing. M3 added 54.

| Suite | Tests | Proves |
|---|---|---|
| `InterviewLifecycleTest` | 40 | Every legal and illegal attempt and turn transition, reason/status pairing, finalisation guard |
| `QuestionSelectorTest` | 10 | Determinism, catalogue-order independence, within-plan uniqueness, exposure preference, pinned slots |
| `InterviewEngineIntegrationTest` | 27 | Plan materialisation, provenance, idempotency of every command, follow-up graph and budgets, off-topic suppression, completion, early finish, derived state |

`QuestionSelectorTest` uses a stub catalogue because the behaviour under test is
how a pool is *chosen from*, and that must hold for any pool in any order.

## 12. Two things the tests caught

**The engine refused to probe every answer, and was right to.** The deterministic
provider marks an answer `answerOffTopic` when no criterion matches at all, and
`FollowUpSelector` deliberately does not probe an off-topic answer. The first
fixture used generic filler rubrics that no real answer matched, so every answer
was off-topic and no follow-ups were produced. The fixture was wrong, not the
engine — and the behaviour now has its own test.

**Finishing early could never complete.** Closing requires all turns settled, but
turns the candidate never reached stayed `PENDING` forever. Fixed by recording
them as skipped, which also required allowing `PENDING → SKIPPED` — a transition
that represents "never answered" rather than a candidate's decision.

## 13. Known limitations

1. **Follow-up verdict merging is not implemented.** The graph is built
   correctly; merging a probe's verdict into its parent criterion is a later
   milestone, as M2 anticipated.
2. **No job worker.** `evaluateAnswer` is called explicitly; M4's queue will
   drive it from `ANSWERED` turns instead of the request thread.
3. **No expiry sweeper.** Expiry is enforced when an attempt is next touched,
   not proactively. `InterviewRepository.findExpired` exists for the scheduled
   sweep M4 adds.
4. **`finalizeCompletion` generates no report.** M5's work slots in there.
5. **No HTTP surface, no authentication.** A controller will call
   `InterviewService`; the candidate-ownership check it needs is not yet
   present, because there is no authenticated caller to check against.
6. **Question selection ignores difficulty progression.** A slot's difficulty is
   honoured, but the engine does not adapt difficulty to how the candidate is
   doing. That is a deliberate Phase 0 non-goal.
