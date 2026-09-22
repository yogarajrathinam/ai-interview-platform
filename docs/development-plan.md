# Development Plan

> Supersedes `09-development-plan.md`. Milestone numbering is unchanged;
> content and scope reflect the approved review
> ([10-architecture-review.md](10-architecture-review.md)).
>
> Last updated: M0 + M1 complete.

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
| **M2** | AI gateway + evaluation engine, headless, golden set v0 | Next — **risk-reduction milestone** |
| **M3** | Catalogue: question bank and templates, publish services, dry-run tool | |
| **M4** | Interview engine, job worker, deferred follow-ups | |
| **M5** | Reporting: aggregation, bands, coverage | |
| **M6** | Auth end to end: JWKS verification, JIT provisioning, ownership checks | |
| **M7** | Design system and app shell | |
| **M8** | Candidate experience, including the runner | |
| **M9** | Admin experience | |
| **M10** | Content, hardening, launch | |

Critical path: M1 → M2 → M4 → M5 → M8.

## M2 — Evaluation engine, headless *(next, ≈4 days)*

The risk-reduction milestone, and the reason it comes before any UI.

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

## M3 — Catalogue *(≈3 days)*

Question and template authoring services, publish validation in the application
layer *above* the database gates, plan resolution, `dry-run-evaluation`, and the
seed command that loads content through the same validation path.

## M4 — Interview engine and job worker *(≈4 days)*

Attempt lifecycle, turn materialisation, idempotent answer submission,
`advance`, `complete`, the maintenance sweeper, and the DB-backed worker.
`FollowUpSelector` as a **pure function** with table-driven tests.

**Demo:** drive a whole interview over HTTP, including a deferred follow-up, a
duplicate submit, and a provider outage that leaves the interview usable.

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
