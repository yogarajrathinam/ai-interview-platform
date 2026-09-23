# M2 — Headless Evaluation Engine

> The core business value, built and proven without HTTP, authentication, a
> frontend, or a real AI provider.
>
> Verified 2026-09-23 against PostgreSQL: `mvn clean verify` → **135/135 tests**.

## 1. What M2 delivers

The chain the product rests on, end to end:

```
Answer -> Provider reading -> Validation gates -> Criterion results
       -> Question score -> Skill score -> Interview score
```

Every step is code we own and can test. The only part a model will ever perform
is the *reading* — and even that is bounded by a fixed rubric and checked
afterwards.

**No HTTP endpoint, no authentication, no AI SDK, no frontend.** The engine is
called from tests today and from the job handler in M4.

## 2. Architecture

```
evaluation/
├── api/            the published contract — the only package other modules import
│   ├── EvaluationProvider          port: "read this answer against this rubric"
│   ├── EvaluationEngine            grade one answer, persist nothing
│   ├── AnswerEvaluationService     grade a stored answer and record it
│   ├── EvaluationRequest           self-contained: question + rubric + answer
│   ├── ProviderEvaluation          what a provider returns (observations)
│   ├── EvaluationResult            what we believe after validation (+ score)
│   ├── Verdict · EvaluationStatus · EvaluationPhase
│   └── EvaluateAnswerCommand · EvaluationProviderException
├── application/
│   ├── DefaultEvaluationEngine     provider -> validate -> score
│   ├── ProviderResultValidator     the gates
│   ├── EvidenceValidator           the anti-hallucination gate
│   ├── DefaultAnswerEvaluationService  idempotency, snapshot, accounting
│   └── EvaluationWriter            the one transactional write
├── domain/
│   ├── ScoringPolicy               credit table + rounding, versioned
│   ├── AnswerScorer                criteria -> question score
│   ├── InterviewScorer             questions -> skill -> overall
│   └── EvaluationEntity · EvaluationCriterionResultEntity
└── infrastructure/
    ├── DeterministicEvaluationProvider
    └── EvaluationRepository · EvaluationCriterionResultRepository
```

Two supporting ports were added to other modules:

| Port | Module | Why |
|---|---|---|
| `QuestionCatalog` | `question` | Read the pinned question version and its rubric — by version id only |
| `AiInvocationRecorder` | `ai` | Record every provider call, including failures |

### The boundary that shapes everything

**`evaluation` does not depend on `interview`** — enforced by a new ArchUnit
rule. The engine is handed an answer, a question version and a rubric; it does
not know what an interview is.

That is not purism. It is what lets the same engine serve the admin rubric
dry-run in M3, where there is no interview, no turn and no stored answer — and
it is why `EvaluationRequest` carries the question and rubric as a snapshot
rather than ids to resolve later.

The same boundary explains what the engine **does not** do: it never updates
`interview_questions.status`. That row belongs to the interview module. The
outcome reports the phase reached, and the orchestrator applies it (M4).

## 3. Contracts

**`EvaluationRequest`** — self-contained. Attempt identifiers are nullable so a
dry run can use the same type.

```
interviewId?  interviewQuestionId?  answerId?
questionVersionId  rubricVersion
question: { skillId, promptText, contextText?, referenceAnswer? }
rubric:   [ { id, code, label, expectation, weightBp, tier } ]
answer:   { text, inputMode }
```

**`ProviderEvaluation`** — observations, not a score. Per criterion: a verdict,
a confidence, a *claimed* quote, a comment. Plus `followUpNeeded` (a signal, not
a command), `injectionSuspected`, `answerOffTopic`, and `modelReportedScore`,
which enters no calculation.

**`EvaluationResult`** — what we believe afterwards: verdicts possibly
downgraded, evidence spans computed by us, and `derivedScore` computed by
`AnswerScorer`. Strengths, weaknesses and missing concepts are *derived* from
the criterion outcomes rather than stored, so they can never drift from the
verdicts that produced the score.

## 4. Scoring formula

```
credit(verdict)      MET 1.00 | PARTIAL 0.50 | MISSING 0.00 | CONTRADICTED -0.25

low confidence       credit' = 0.5 + (credit - 0.5) x confidence / 0.5   (below 0.5 only)

questionScore = 10 x clamp( SUM(weight_i x credit_i) / SUM(weight_i), 0, 1 )

skillScore    = SUM(qWeight x qScore) / SUM(qWeight)          graded questions of that skill
overallScore  = SUM(skillWeight x skillScore) / SUM(skillWeight)   covered skills only
```

Rounding is half-up to two decimals, matching `numeric(4,2)`.

Four rules carry real meaning:

- **The clamp at zero** stops a single contradiction producing a negative
  question score, while still costing more than silence.
- **Low confidence pulls toward the midpoint**, so an unsure grader produces a
  middling verdict rather than a confident wrong one — deliberately biased
  toward the centre, because a wrongly harsh score destroys trust fastest.
- **A skipped answer scores 0 and is counted.** Hiding it would corrupt
  comparability.
- **An answer we failed to grade is excluded** from numerator *and* denominator
  and reported as reduced coverage. Our outage is not the candidate's fault.

Follow-up turns carry `weight_bp = 0` by database constraint, so they move no
average. They re-probe a criterion the candidate under-answered; independent
weight would let a weak topic dominate purely because it was asked twice.

Skill weights are never hard-coded — they arrive as data from the pinned
template version.

> The aggregation lives in `evaluation.domain.InterviewScorer` so the maths
> exists exactly once. Reporting (M5) consumes it to build and persist a report;
> it does not reimplement it.

## 5. Lifecycle

Four phases, **derived and never stored**:

| Phase | Derived from |
|---|---|
| `PENDING` | an answer exists, no current evaluation |
| `PROCESSING` | a grading attempt is in flight (a `RUNNING` job, from M4) |
| `COMPLETED` | current evaluation with `status = SUCCEEDED` |
| `FAILED` | current evaluation with `status = FAILED_*` |

Legal transitions: `PENDING → PROCESSING`, `PROCESSING → COMPLETED|FAILED`, and
`COMPLETED|FAILED → PROCESSING` for re-evaluation. Nothing returns to
`PENDING`: an answer awaiting its first grading is a state that cannot recur.

**No new table, no new column, no new state machine store.** The approved schema
already carries every fact, and a stored phase would be a second source of truth
able to disagree with the evaluation rows themselves.

## 6. Provider abstraction

```
EvaluationEngine  ->  EvaluationProvider (port)  ->  DeterministicEvaluationProvider
                                                     (later: AiEvaluationProvider)
```

The port is deliberately narrow: a provider reads a text against a fixed rubric
and reports what it found. It cannot score, weight, decide follow-ups, or see
the rest of the interview. Implementations persist nothing.

**`DeterministicEvaluationProvider`** grades by term overlap against each
criterion's `expectation` — MET at ≥60% of significant terms, PARTIAL at ≥30%,
MISSING below. Evidence is the answer sentence with the most matches, returned
verbatim.

It is honest about what it is: term overlap is not comprehension, it will mark a
fluent wrong answer as correct, and it can never return `CONTRADICTED` because
detecting a false claim requires knowing what is true. Those are precisely the
jobs the real provider will do. Its value is that every part of the pipeline
*around* the model is proven before a single model call is made — so defects
found later are prompt problems, not plumbing.

A vendor SDK may appear only inside an `infrastructure` package; a new ArchUnit
rule fails the build otherwise.

## 7. Validation gates

| Gate | Failure mode | Consequence |
|---|---|---|
| Result present | provider returned null | whole evaluation `FAILED_VALIDATION` |
| No duplicate criteria | same criterion judged twice | whole evaluation rejected |
| No unknown criteria | criterion not in the rubric | whole evaluation rejected |
| Complete criterion set | a rubric criterion unjudged | whole evaluation rejected |
| Blank answer | any verdict at all | all criteria forced to `MISSING` |
| **Evidence verbatim** | quote not in the answer | that criterion downgraded, `evidenceRejected` recorded |
| Confidence bounds | outside 0–1 | clamped |
| Follow-up target | criterion outside the rubric | dropped |
| Model score sanity | outside 0–10 | discarded |

The asymmetry is deliberate. A malformed criterion set means the provider did
not do the task and none of its judgements can be relied on. Unsupported
evidence costs only that criterion — rejecting the whole evaluation would throw
away good signal, while accepting it would manufacture a score from nothing.

**Evidence offsets are computed by us**, never taken from the provider. Matching
tolerates case and collapsed whitespace — a grader re-typing a quote across a
line break has still read the answer — and nothing else. A paraphrase is not
evidence.

## 8. Idempotency

No `idempotency_keys` table, no framework. The partial unique index
`uq_evaluations_one_current ON evaluations(answer_id) WHERE is_current` makes
two current evaluations impossible, and the service is built around it:

| Situation | Behaviour |
|---|---|
| Already graded, no re-run asked | returns the stored evaluation; **the provider is never called**, so a retried job costs nothing |
| Re-evaluation requested | supersedes (`is_current = false`) and inserts — append-only, attributed via `triggered_by` |
| Two concurrent callers | PostgreSQL rejects the second insert; the loser returns the winner's result rather than failing work that succeeded |

Superseding and inserting happen in one transaction, so an answer is never
without a current evaluation.

Superseding uses a targeted JPQL update rather than load-and-mutate: Hibernate's
dirty checking emits an UPDATE covering every mapped column, and
`trg_eval_20_append_only` rejects any statement changing a column other than
`is_current`.

## 9. Failure handling

The requirement is not that failures are rare; it is that they are survivable
and visible.

| Failure | Result |
|---|---|
| Timeout / rate limit / outage | `FAILED_PROVIDER` with the reason; retryable |
| Unparseable or schema-violating output | `FAILED_VALIDATION`; not retryable |
| Unexpected SDK exception | contained; never escapes the engine |
| Accounting write fails | logged and swallowed — it must never sink an evaluation |

A failed evaluation stores **no criterion results**: recording verdicts we never
obtained would be evidence of grading that did not happen. The candidate's
answer is written before grading begins, so no failure can lose their work.

Failed calls are still recorded in `ai_invocations` — including *which provider
failed*, without which "how often is this provider failing?" is unanswerable.
The recorder runs in `REQUIRES_NEW` so the record survives the caller's rollback.

## 10. Reproducibility

The engine reads the rubric **by pinned version id**, never by question family.
`QuestionCatalog` deliberately exposes no "current version of this question"
method — grading against anything else would silently rewrite history.

Every evaluation stores the provenance needed to explain it years later:
`evaluation_version` (credit table + algorithm), `prompt_version`,
`rubric_version`, `question_version_id`, `provider`, `model`.

Proven by test: an attempt pins v1; v1 is archived and a stricter v2 published;
re-grading the attempt still scores 10.00 against v1's rubric and records
`question_version_id = v1`.

## 11. AI invocation tracking

Every call writes one `ai_invocations` row: purpose, provider, model, prompt
version, request fingerprint, status, latency, and correlation to the interview
and answer.

Token counts and cost are **left null** for the deterministic provider. Nothing
was consumed and nothing was spent — zeros would be a claim, null is the truth,
and fabricated zeros would quietly corrupt every cost-per-interview figure the
table exists to answer.

The fingerprint is `sha256(promptVersion ‖ model ‖ questionVersionId ‖ answer)`.
Hashing rather than storing is deliberate: answer text is personal data, and the
column exists for correlation, not content.

## 12. Test strategy

135 tests, all passing.

| Suite | Tests | Proves |
|---|---|---|
| `ScoringTest` | 26 | Credit table, question/skill/overall scoring, weights, rounding, boundaries, zero-weight follow-ups, coverage |
| `EvaluationPhaseTest` | 21 | Every legal and illegal lifecycle transition, and phase derivation |
| `EvidenceValidatorTest` | 10 | Exact, case- and whitespace-tolerant matching; fabrications and paraphrases rejected |
| `EvaluationEngineTest` | 17 | All gates, provider failures, and that the provider's score is ignored |
| `AnswerEvaluationIntegrationTest` | 10 | Persistence, provenance, evidence offsets, idempotency, re-evaluation, reproducibility, accounting |
| `EvaluationFailureIntegrationTest` | 5 | Failure recorded, no fabricated verdicts, answer survives, retryable |
| `ArchitectureTest` | 15 | Boundaries, including the two new M2 rules |
| `DatabaseInvariantTest`, `MigrationAndMappingTest` | 31 | Unchanged from M1 |

Unit tests use **stub providers**, not the deterministic one: the behaviour under
test is what happens when a provider *misbehaves*, and a well-behaved provider
cannot exercise it.

The central property has its own test: a provider returning all-`MISSING`
verdicts while claiming `modelReportedScore = 10.00` yields a stored score of
`0.00`.

## 13. Architecture rules

Two added:

- **`evaluation` must not depend on `interview`** — grading stays usable without
  an interview.
- **Provider SDKs stay in `infrastructure`** — a tripwire for the milestone that
  adds a real vendor.

One refined: `domain_does_not_depend_on_api` became
`domain_does_not_depend_on_the_transport_layer`. The original forbade
`domain → api` outright, which conflicted with a boundary that matters more — a
module's published value types (`Verdict`, `EvaluationStatus`) must live in its
`api` package, or consumers of the contract are forced to import
`<module>.domain` and break the cross-module rule. What the rule was really
protecting, the domain staying free of the transport layer, is now stated
directly by naming controllers and web types, and is stricter for it.

## 14. Known limitations

1. **The deterministic provider is not a grader.** Term overlap will mark a
   fluent wrong answer correct and can never detect a contradiction. It exists
   to prove the pipeline, not the evaluation quality.
2. **No golden set yet.** Measuring agreement with a human grader requires a
   real provider; that gate belongs with the AI integration milestone.
3. **Turn status is not updated.** The engine reports the phase; applying it to
   `interview_questions` belongs to the orchestrator in M4.
4. **Follow-up criterion merging is not implemented.** Follow-ups currently
   contribute nothing (weight 0). Merging their verdicts back into the parent
   criterion needs the parent/child turn graph, which the interview module owns.
5. **No retry or backoff.** Failures are recorded as retryable; the job queue
   that re-drives them arrives in M4.
6. **Scoring policy is compile-time.** `ScoringPolicy.VERSION` is stamped onto
   every row, so changing the credit table is a versioned act — but it is a code
   change, not configuration. That is deliberate at this stage.
