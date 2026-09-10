# 09 — Development Plan

> Status: Draft for approval · Depends on: 01–08

## 1. Reasoning about sequence

Three constraints shape the order:

1. **The riskiest thing must be validated earliest.** The product bet is evaluation
   quality (01 §2). Anything that lets us grade a real answer against a real rubric
   sooner beats anything that makes the UI nicer.
2. **The schema is the highest-coupling artefact.** Getting `interview_questions`,
   `evaluations` and `evaluation_criterion_results` wrong costs a data migration with
   live results. It goes first, with tests.
3. **Each step must end in something demonstrable.** No milestone is "refactoring
   week". Every step below produces a thing you can run.

Deliberately *not* first: authentication UI, landing page, design polish. They are
necessary, they are not risky, and doing them first delays the only question that
matters.

## 2. Build order

### M0 — Repository and foundations *(≈2 days)*

- Monorepo: `/backend` (Spring Boot 3.3, Java 21, Maven), `/frontend` (Vite + React +
  TS), `/docs`, `/.github/workflows`.
- Backend skeleton with the module package structure from 06 §3 (empty but present),
  `shared/` cross-cutting: problem-details error handling, trace filter, `Clock` bean,
  UUIDv7 `IdGenerator`, `@ConfigurationProperties` records with validation.
- Docker Compose Postgres for local dev; Testcontainers wired for tests.
- ArchUnit test module with the boundary rules — added now so they never have to be
  retrofitted against violations.
- CI: build, test, ArchUnit, gitleaks, frontend lint/typecheck.
- **Demo:** `GET /health/readiness` returns 200; a deliberate boundary violation fails CI.

### M1 — Database baseline *(≈2 days)*

- `V1__baseline.sql` implementing 04 §3 in full (all tables, constraints, indexes).
- `V2__seed_skills.sql` with the 8 launch skills.
- JPA entities with `ddl-auto: validate`; a Testcontainers test that migrates from
  scratch and validates.
- Invariant tests: one-live-attempt, one-published-version, one-current-evaluation,
  one-answer-per-turn all rejected at the database level.
- **Demo:** migration test green; constraint tests prove the invariants.

### M2 — Evaluation engine, headless *(≈4 days)* ← the risk-reduction milestone

- `ai` module: `AiClient` port, `AiGateway` (timeout, retry, breaker, budget,
  `ai_invocations` accounting), one real provider adapter + `MockAdapter`.
- `evaluation` module: prompt v1 resource, `RubricEvaluator`, `ResponseValidator`
  (gates G1–G7), `ScoreCalculator`, persistence.
- A CLI/test entry point: given a question version + answer text, print the full
  evaluation and derived score.
- Golden set v0: 20 hand-labelled cases across 3 questions; harness runs and reports
  the metrics in 07 §11.
- **Demo:** grade a real HashMap answer end to end, show criterion verdicts, evidence
  and derived score; show a hallucinated-evidence case being downgraded.
- **Gate:** if criterion agreement is below ~70% here, stop and fix the approach
  before building anything on top of it.

### M3 — Content: catalog + template modules *(≈3 days)*

- `catalog`: questions, versions, rubric criteria, publish validation, selection.
- `template`: templates, skills, slots, publish validation, plan resolution.
- Admin service-layer APIs (no UI yet) + `dry-run-evaluation`.
- Seed command loading the initial bank through the same validation path.
- **Demo:** publish a template via API; `preview-plan` resolves 8 concrete questions.

### M4 — Interview engine + job worker *(≈4 days)*

- `interview`: attempt lifecycle, turn materialisation, answers (idempotent),
  drafts, `advance`, `complete`, sweeper.
- `shared/jobs`: DB queue with `SKIP LOCKED`, backoff, dead-lettering, reaper.
- `EVALUATE_ANSWER` handler wiring M2 to M4.
- `FollowUpPolicy` as a pure function with exhaustive table-driven tests.
- **Demo:** drive a whole interview through HTTP with curl/`.http` files, including a
  follow-up, a duplicate submit, and a provider outage that leaves the interview usable.

### M5 — Reporting *(≈2 days)*

- `reporting`: skill aggregation, overall score, bands, coverage, strengths/gaps,
  optional AI summary with deterministic fallback, `GENERATE_REPORT` job.
- **Demo:** `GET /interviews/{id}/result` returns a complete report; the maths matches
  a hand calculation in a test.

*Backend is now feature-complete and verifiable without a single screen.*

### M6 — Auth end to end *(≈2 days)*

- Supabase project (staging + prod), asymmetric JWT signing keys, JWKS verification,
  JIT provisioning, role resolution, ownership checks, admin route separation.
- Security tests: tampered/expired/wrong-audience tokens, wrong-owner access → 404,
  candidate → admin route → 403.
- Frontend auth: Supabase client, `AuthProvider`, Axios interceptors, guards.
- **Demo:** sign up in the browser, hit `/me`, get provisioned.

### M7 — Design system + app shell *(≈3 days)*

- `theme/` tokens, palette, typography, component defaults (03 §3).
- Primitives and patterns (03 §4) with a local component gallery route.
- Layouts, routing, `QueryBoundary`, error boundary, toast, TanStack Query setup.
- **Demo:** the gallery page shows every component in every state.

### M8 — Candidate experience *(≈5 days)*

- Landing, dashboard, template catalogue and detail/setup.
- **The runner**: state-driven, autosave, polling with `retryAfterMs`, follow-up
  display, refresh-safe, expiry handling.
- Completion/waiting screen with real progress; result page with `RubricResultList`
  and evidence highlighting; history.
- **Demo:** the full candidate journey in a browser.

### M9 — Admin experience *(≈4 days)*

- Dashboard, candidates, attempts list and inspection (with provenance),
  question bank authoring with rubric editor + dry-run, template authoring, jobs view.
- **Demo:** author a question and a template from scratch in the UI, then take that
  interview as a candidate.

### M10 — Content, hardening, launch *(≈5 days)*

- Question bank to ~120 published questions across the 8 skills; 3–5 templates.
- Golden set to 60–100 cases; full harness run recorded.
- Rate limiting, security headers, CORS, retention jobs, metrics and the alerts in
  08 §13, structured logging review (no answer text in logs).
- Backup restore rehearsal; runbooks (provider outage, dead jobs, key rotation).
- Playwright happy-path E2E; accessibility pass; responsive pass.
- Privacy policy, terms, AI-processing disclosure on the setup screen.
- **Demo:** launch.

**Total ≈ 36 working days** of focused effort for one experienced engineer. The
content work in M10 is the most commonly underestimated item and is the one to start
in parallel from M3 onward.

## 3. Parallelisation

If more than one person is available: M7/M8 (frontend) can start after M6 against a
seeded backend; question authoring (M10 content) can start after M3 by anyone with
domain knowledge; the golden set can be labelled by anyone technical from M2.

Critical path: M1 → M2 → M4 → M5 → M8.

## 4. Definition of done (every milestone)

- Tests: unit for logic, Testcontainers integration for persistence, MockMvc for web.
- ArchUnit clean; no new boundary suppressions.
- Migrations forward-only and tested from scratch.
- No secret, PII, or answer text in logs.
- OpenAPI regenerated; breaking-change diff reviewed.
- New endpoints have an authorisation test including a wrong-owner case.
- Errors return problem details with a stable `code`.
- Runs locally with `docker compose up` + two commands, documented in the README.

## 5. Environments and delivery

| | Local | CI | Staging | Prod |
|---|---|---|---|---|
| DB | Docker/Testcontainers | Testcontainers | Supabase (separate project) | Supabase |
| Auth | Supabase dev project | stubbed | Supabase staging | Supabase prod |
| AI | `MockAdapter` | `MockAdapter` + WireMock | real, low budget | real |
| Deploy | `bootRun` / `vite dev` | — | on merge to `main` | on tag |

Branching: short-lived feature branches off `main`, PR required, CI green to merge.
Migrations run as a discrete pre-deploy step. Rollback = redeploy the previous image;
migrations are backwards-compatible for one release so that is always safe.

## 6. What we will measure from day one

`interview.started/completed/abandoned`, evaluation latency and failure reasons,
`ai.cost.micros` per interview, schema-repair rate, evidence-rejection rate, job
queue depth and oldest job age, and the golden-set metrics per prompt/model change.
These are the numbers that tell us whether the product works, in the order they'd
tell us something is wrong.

## 7. Assumptions

- One primary engineer, roughly full-time.
- Supabase projects can be created immediately (a dependency for M6 — provision them
  during M0 so they're not on the critical path).
- Someone with strong Java/Spring/SQL/React knowledge is available to author
  questions and label the golden set. **This is the hardest resource to secure and
  the easiest to under-plan.**
- AI provider account with a budget of ~$100 for development and the golden set runs.

## 8. Risks to the plan

| Risk | Mitigation |
|---|---|
| M2 reveals that evaluation quality is inadequate | It is deliberately early and has an explicit stop-gate; a pivot there costs 6 days, not 6 weeks |
| Question authoring lags and launch has 30 questions | Start content at M3, track a weekly count, treat 120 as a launch criterion |
| Frontend scope creep in M8/M9 | Screen inventory (02 §6) is fixed; anything not listed is Phase 1 |
| Admin UI (M9) balloons | It is a content and inspection tool; no analytics, no charts beyond counts |
| Golden-set labelling is skipped under time pressure | It is the gate on every future model change; treated as a launch blocker, not a nice-to-have |
| Provider pricing or availability changes mid-build | Gateway abstraction + per-purpose config makes a swap a measured decision |

## 9. Immediately after Phase 0 (not now, recorded so it isn't designed for now)

In likely order: voice interviews (the largest differentiator, and the architecture
is ready), candidate skill profile over time, SSE instead of polling, institute
tenancy, AI-generated follow-up text behind moderation, coding challenges,
self-service data export/deletion.
