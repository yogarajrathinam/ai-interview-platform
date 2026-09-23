# M5 — Real AI Evaluation Provider + Golden Set

**Status:** implemented, uncommitted
**Depends on:** M2 (evaluation engine), M4 (job worker)
**Schema changes:** none

M2 built the grading pipeline around a deterministic term-matcher, deliberately,
so that validation, evidence checking, scoring, persistence and idempotency could
all be proven correct before a model was involved. M5 puts a real model behind
the same port and adds the only artefact that can say whether its judgement is
any good.

Nothing in `evaluation.api`, `evaluation.domain` or `evaluation.application`
changed. That was the test of whether the M2 port was real, and it held.

---

## 1. What was built

| Concern | Where |
|---|---|
| Vendor adapter | `evaluation/infrastructure/anthropic/AnthropicEvaluationProvider.java` |
| Constrained output shape | `evaluation/infrastructure/anthropic/GradingResponse.java` |
| Versioned prompt | `evaluation/infrastructure/anthropic/EvaluationPrompt.java` + `resources/prompts/answer-evaluation-v1.md` |
| Provider selection & client | `evaluation/infrastructure/anthropic/AnthropicProviderConfiguration.java` |
| Configuration | `AppProperties.Ai` |
| Golden dataset | `src/test/resources/golden/cases/*.json` |
| Dataset loader | `support/golden/GoldenCase.java`, `GoldenSet.java` |
| Agreement metric | `support/golden/AgreementReport.java` |
| Tests | `AnthropicProviderAdapterTest`, `GoldenSetStructureTest`, `AgreementMetricTest`, `GoldenSetLiveEvaluationTest` |

---

## 2. The model never scores anything

This was the central constraint of the architecture and it is now enforced in
three independent places, each of which would be sufficient alone:

1. **The schema has no score field.** `GradingResponse` gives the model nowhere
   to put a number. Withholding the field is a stronger guarantee than
   discarding the value, because there is no value to discard.
2. **The prompt says so**, and says any number supplied is discarded.
3. **The adapter hard-codes `modelReportedScore` to null.**

Scoring remains entirely in `AnswerScorer` and `InterviewScorer`, from verdicts
and basis-point weights, exactly as in M2.

The model is also **not told the criterion weights**. A grader that knows which
criteria are worth more has an incentive to grade the heavy ones differently,
and there is no reason it needs the information to answer "does this answer
assert this claim?". `AnthropicProviderAdapterTest` asserts the weights do not
appear in the request body.

---

## 3. Structured output, and two constraints it imposes

The SDK derives a JSON schema from the `GradingResponse` record and constrains
generation to it, so "the model replied in prose" is not a runtime failure mode
we have to parse our way out of. Two properties of the generated schema were
verified against the SDK rather than assumed, and both changed the design:

**Every record component is `required`, and none is nullable.** So the two
fields that are optional in spirit — `evidenceQuote` for a `MISSING` verdict,
and `followUpTargetCriterionId` when no follow-up applies — use `""` to mean
absent, and the adapter maps blank to null. Asking for JSON `null` against a
required string would have pushed the model toward inventing a value, which for
an evidence quote is precisely the failure the whole evidence gate exists to
catch.

**A typed enum becomes a schema constraint.** `verdict` is declared as the real
`Verdict` enum, so the schema carries
`enum: [MET, PARTIAL, MISSING, CONTRADICTED]` and an unrecognised verdict is
structurally impossible rather than merely discouraged by prompt wording. This
is the one place a vendor DTO names a published enum, and it is worth it: a
constraint the provider enforces beats one we check afterwards.

### A trap worth recording

`outputConfig` is a **whole-object setter**. `outputConfig(Class)` and
`outputConfig(OutputConfig)` overwrite each other in *either* order:

| Order | Result |
|---|---|
| effort, then schema | effort silently dropped |
| schema, then effort | **schema silently dropped** |

The second is the dangerous one: it fails open. The request still succeeds, the
model answers in prose, and every evaluation fails as `INVALID_OUTPUT` — a
failure that looks like a model problem and is really a wiring bug. The adapter
therefore generates the schema, reads it back, and merges the effort into that
same object via `toBuilder()`. `AnthropicProviderAdapterTest` asserts both are
present in one request, because either alone still looks correct.

---

## 4. Failure handling

Every vendor failure is mapped to an `EvaluationProviderException.Reason` the
engine already understood from M2. The split that matters is retryable versus
permanent, because a permanent failure retried four times burns a job's attempts
to reach an identical outcome.

| Condition | Reason | Retryable |
|---|---|---|
| 429 rate limit | `RATE_LIMITED` | yes |
| 5xx / service fault | `PROVIDER_ERROR` | yes |
| Network failure, client timeout | `TIMEOUT` | yes |
| 401 / 403 — bad or revoked credential | `INVALID_OUTPUT` | no |
| 400 / 404 / 422 — rejected request, wrong model id | `INVALID_OUTPUT` | no |
| Refusal or truncation — no content block | `INVALID_OUTPUT` | no |
| Payload not matching the schema | `INVALID_OUTPUT` | no |
| Unparseable criterion id | criterion dropped | — |
| Unrecognised verdict | prevented by the schema; null reads as `MISSING` | — |
| Fabricated evidence | verdict downgraded by `EvidenceValidator` (M2) | — |
| Criterion invented or omitted | rejected by `ProviderResultValidator` (M2) | — |

A refusal is deliberately *not* treated as a transport fault. It arrives as a
successful HTTP response carrying nothing gradable; retrying reproduces it, and
synthesising an empty verdict set would let a refusal read as "the candidate met
nothing" — a real score, silently wrong.

**SDK retries are disabled** (`maxRetries(0)`). The job queue owns retries and
backoff. Two retry budgets multiplied together would make the effective attempt
count unclear and would hide rate-limiting from the very metrics meant to show
it.

---

## 5. Security posture

- **No secret is in the repository.** The key is read from
  `app.ai.api-key`, bound from `ANTHROPIC_API_KEY`. `.env.example` carries a
  placeholder only, and gitleaks runs over full history in CI.
- **The key is never logged.** `AppProperties.Ai.describe()` returns provider,
  model, timeout and effort — deliberately not the credential — and it is the
  only thing logged at startup.
- **Candidate answers stay out of ordinary logs.** Provider error text can echo
  the request back, and the request contains the candidate's words, so the
  adapter logs the failure category and exception type and not the vendor
  message. The cause stays attached to the exception for deliberate
  investigation.
- **Missing credential fails at startup, not at first use.** An environment that
  cannot grade should refuse to start rather than accept interviews and fail
  every evaluation an hour later. The message names the property, never any part
  of the value.
- **Injection is handled as data, not as instructions.** The candidate's text is
  confined to a delimited block, closing delimiters are neutralised, the prompt
  states that the block is data, and `injectionSuspected` is reported as a
  signal. `java-injection-attempt.json` in the golden set is a live example: the
  correct behaviour is to ignore the instruction and grade the technical content
  honestly.
- **Test credentials are synthetic.** `AnthropicProviderAdapterTest` uses an
  obviously fake literal against a local WireMock server that does not
  authenticate.

---

## 6. Provider selection

One property, `app.ai.provider`, with both implementations gated on it:

```
app.ai.provider=deterministic   # default
app.ai.provider=anthropic
```

The default is `deterministic`, so a fresh checkout, the whole test suite and a
misconfigured environment all start without a credential and cannot spend money
by accident. Reaching the paid provider is a deliberate act.

`@Primary` was considered and rejected: it would leave the unused client
constructed and its credential required, which is the opposite of what a
default-safe configuration should do. Gating both beans means exactly one
`EvaluationProvider` can ever exist.

---

## 7. The golden set

Eight hand-labelled cases across JAVA, SPRING_BOOT and SQL, one JSON file each
so a review diff shows exactly which judgement changed. See
`src/test/resources/golden/README.md` for the format and the labelling policy.

The set is chosen to be **hard on purpose**. A set of only clear-cut answers
would report high agreement and prove nothing:

| Case | What it catches |
|---|---|
| `java-hashmap-strong` | baseline; fails means nothing else is worth reading |
| `java-hashmap-hedged` | fluency mistaken for coverage — the `PARTIAL` boundary |
| `java-hashmap-confidently-wrong` | `CONTRADICTED` collapsed into `MISSING` |
| `spring-transactional-partial` | a strong impression treated as full coverage |
| `spring-transactional-minimal` | partial credit for showing up |
| `sql-index-strong` | style penalised over content (deliberately scruffy prose) |
| `sql-index-off-topic` | topical proximity mistaken for an answer |
| `java-injection-attempt` | instruction-following instead of grading |

### The metric

Criterion-level **exact** agreement against the validated result.

- **Criterion-level, not score-level**: agreeing on a score is not evidence of
  agreeing on the reading — two errors in opposite directions cancel and produce
  a number that looks perfect.
- **Exact, not graded**: `MET` against `PARTIAL` is a miss. Partial credit for
  "nearly right" would be a second scoring policy invented inside the
  measurement, and it would flatter the grader precisely on the hedged cases
  where its reading matters most.
- **Measured after validation**, not on the raw provider response, because the
  post-validation verdict is what a candidate is actually scored on. A provider
  whose verdicts are right but whose evidence is fabricated has not earned
  agreement.
- **Omitted criteria count as misses**, never as exclusions. Otherwise failing
  to return a hard criterion would raise the score.

`AgreementMetricTest` drives all of the above with scripted verdicts, because a
bug in the metric would otherwise be invisible — a metric that quietly ignored
omitted criteria would report a *better* number for *worse* behaviour.

### The gate

Default threshold **0.70**, overridable with
`-Dgolden.agreement.threshold=`. It is a **stop-gate, not a target**: below it
the grader is not fit to score a candidate. Above it the number means little on
a set this size, which is why the mismatch list prints on every run, passing or
failing.

---

## 8. Why the live test is not in CI

`GoldenSetLiveEvaluationTest` is tagged `live-ai` and excluded from every profile
except `-Plive-ai`. It calls a paid service and is not deterministic; the same
answers can produce slightly different verdicts between runs. A build that fails
on someone else's rate limit or on ordinary model variance teaches everyone to
ignore red, which is worse than having no gate at all.

So the division is explicit: **CI proves the pipeline and the metric; the live
gate proves the grader.**

```
ANTHROPIC_API_KEY=... mvn -P live-ai test -Dtest=GoldenSetLiveEvaluationTest
```

Without a credential it skips rather than fails — a missing key means "not asked
for". CI additionally asserts that this suite produced *no* report, so it cannot
start running by accident.

---

## 9. Deliberate omissions

- **No cost figure.** The response reports tokens but not money. A figure would
  have to come from a price table compiled into the build, which goes stale
  silently and turns into confidently wrong spend reporting. Tokens are recorded
  faithfully; `costMicros` stays null and cost is derived downstream where rates
  can be maintained.
- **No streaming.** Grading happens off the request thread in a worker; nobody
  is watching the tokens arrive, and streaming would add partial-response
  handling for no benefit.
- **No prompt caching.** The rubric and question change per call; the shared
  prefix is small. Worth revisiting when question volume makes it measurable.
- **No automatic prompt tuning against the golden set.** Tuning a prompt until
  it clears a threshold on the same cases used to measure it is how a gate stops
  meaning anything. Prompt changes are reviewed, versioned, and measured
  afterwards.
- **No migrations.** M5 changes no schema. `ai_invocations` from M2 already
  records provider, model, prompt version, tokens and status.

---

## 10. Prompt versioning

The prompt is an immutable classpath resource, versioned
(`answer-evaluation-v1`) and stamped onto every evaluation and invocation.
Wording changes what a score *means*, so:

- editing the wording means adding `answer-evaluation-v2.md` and bumping the
  version, never rewriting v1;
- old evaluations keep naming a prompt that still says what they were graded
  against.

Reproducibility therefore holds end to end: an evaluation records the pinned
question version, the rubric version, the exact model id (never an alias), the
prompt version, and the scoring policy version.
