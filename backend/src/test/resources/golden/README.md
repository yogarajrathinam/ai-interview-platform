# Golden evaluation set

Hand-labelled answers with the verdict a human reviewer expects for each rubric
criterion. This is the only artefact in the repository that can tell us whether
the grader is any good, as opposed to merely consistent.

## Why it exists

Every other test proves the pipeline is *correct*: that scores are computed from
verdicts, that evidence is verified, that a failed provider costs nobody a mark.
None of them can tell whether the model's reading of an answer is *right*. A
prompt change that quietly makes the grader harsher would pass the entire suite.
This set is the control.

## What a case is

One JSON file per case in `cases/`, one case per file so a review diff shows
exactly which judgement changed. The shape:

```json
{
  "id": "java-hashmap-strong",
  "skill": "JAVA",
  "notes": "Why this case is labelled the way it is.",
  "question": { "promptText": "...", "referenceAnswer": "..." },
  "criteria": [
    { "code": "THREAD_SAFETY", "expectation": "...", "weightBp": 5000, "tier": "CORE" }
  ],
  "answer": "The candidate's words.",
  "expected": { "THREAD_SAFETY": "MET" }
}
```

`expected` maps every criterion `code` to one of `MET`, `PARTIAL`, `MISSING`,
`CONTRADICTED`. A case must label every criterion it declares — a partially
labelled case is rejected by `GoldenSetStructureTest` rather than silently
scored against a smaller denominator.

Criterion UUIDs are derived from `<case id>:<code>`, so the dataset carries no
opaque identifiers and the ids stay stable across runs without being maintained
by hand.

## Labelling policy

The label is what a competent reviewer would defend, not what the model
happens to produce. Three rules keep that honest:

- **Label before running.** A label edited to match an observed output measures
  nothing. If a run disagrees and the run is right, fix the label in its own
  commit, with the reason in `notes`.
- **Label against the criterion, not the answer's quality.** An eloquent answer
  that never makes the claim is `MISSING`.
- **`CONTRADICTED` means asserted-and-wrong**, not omitted. The distinction
  carries different credit and very different feedback, so it is labelled
  deliberately.

The set deliberately includes cases the grader should find hard: hedged answers
that sit between `PARTIAL` and `MET`, a confidently wrong answer, an off-topic
answer, and an answer that tries to instruct the grader. A set of only clear-cut
cases would report a high agreement score and tell us nothing.

## How it is used

- `GoldenSetStructureTest` (always runs) validates the dataset itself: every
  criterion labelled, weights summing to 10000, unique codes, no empty answers.
- `AgreementMetricTest` (always runs) validates the metric arithmetic against
  scripted verdicts, so a bug in the measurement cannot be mistaken for a change
  in grader quality.
- `GoldenSetLiveEvaluationTest` (tagged `live-ai`, opt-in) runs the real provider
  over the set and enforces the agreement gate. It is excluded from CI because
  CI must not depend on a paid, non-deterministic external service.

Run the live gate with a real credential:

```
ANTHROPIC_API_KEY=... mvn -P live-ai test -Dtest=GoldenSetLiveEvaluationTest
```

## On the threshold

The gate is criterion-level exact agreement, defaulting to 0.70
(`-Dgolden.agreement.threshold=`). It is a **stop-gate, not a target**: below it
the grader is not fit to score a candidate and the build fails. Above it, the
number means little on a set this size — the mismatch list is the useful output,
and it is printed on every run precisely so a passing score does not get read as
"the grader is fine".
