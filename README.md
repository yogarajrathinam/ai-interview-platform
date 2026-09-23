# AI Interview Platform

AI-powered technical mock interviews with **rubric-anchored, evidence-backed
evaluation**. The differentiator is not that an AI asks questions — it is that
every score traces to a named rubric criterion and a quoted span of the
candidate's own answer, and stays reproducible years later.

> **Status: M0–M5A complete.** A whole interview runs end to end in code:
> questions and templates are authored and published, an attempt is planned from
> them, answers are stored and graded asynchronously against the rubric the
> candidate actually saw, weak areas are probed with follow-ups, and the attempt
> completes. Grading runs off the request thread through a database-backed job
> queue, and the queue is inspectable.
>
> **A real AI provider now grades**, behind the same `EvaluationProvider` port,
> returning schema-constrained criterion verdicts with verbatim evidence — and
> never a score, which the backend still computes. It is off by default
> (`app.ai.provider=deterministic`), so nothing needs a credential to build or
> test. A hand-labelled golden set and a criterion-level agreement gate measure
> whether its judgement is any good; that gate is opt-in and deliberately not in
> CI.
>
> **A candidate can now sit the interview in a browser.** M5A adds a thin HTTP
> API over the existing services and a minimal React UI: start, answer, wait
> while grading runs asynchronously, receive follow-ups, finish, and see a
> result with per-criterion verdicts and quoted evidence. The API is a
> **development slice with no authentication** and is reachable only when
> `app.environment` is `local` or `test` — see
> [docs/m5a-http-api-candidate-ui.md](docs/m5a-http-api-candidate-ui.md) §9.
>
> There is still **no authentication or authorization**. Catalogue authoring
> records who authored what but verifies nothing — see
> [docs/m4-worker-operations-catalogue.md](docs/m4-worker-operations-catalogue.md)
> §14. Milestone detail:
> [M0–M1](docs/m0-m1-implementation.md),
> [M2](docs/m2-evaluation-engine.md),
> [M3](docs/m3-interview-engine.md),
> [M4](docs/m4-worker-operations-catalogue.md),
> [M4.5](docs/m5-ai-provider-golden-set.md),
> [M5A](docs/m5a-http-api-candidate-ui.md).

## Quick start

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21** | Java 17 will not compile this project |
| Maven | 3.9+ | Or use the bundled toolchain, below |
| PostgreSQL | 15+ | A container, a local server, or Supabase |
| Docker | any | Optional — only for the Testcontainers path |

### Run the tests

```bash
cd backend

# Architecture tests only — no database required.
mvn test -Pno-docker

# Everything, using Testcontainers (requires a container runtime).
mvn test

# Everything, against a PostgreSQL you already have running.
export TEST_DATABASE_URL=jdbc:postgresql://localhost:5433/interview_platform_test
export TEST_DATABASE_USERNAME=postgres
export TEST_DATABASE_PASSWORD=<your local test password>
mvn test
```

The third form exists because Docker Desktop needs administrator rights to
install, and that is a hard stop on a locked-down workstation. Both paths
migrate an empty database with Flyway and prove the same invariants.

None of these calls a real AI provider or needs a credential. To run the
grader-quality gate against the hand-labelled golden set — which does both, and
costs money per case:

```bash
ANTHROPIC_API_KEY=... mvn -P live-ai test -Dtest=GoldenSetLiveEvaluationTest
```

It is tagged `live-ai` and excluded from every other profile, including the
default. See [docs/m5-ai-provider-golden-set.md](docs/m5-ai-provider-golden-set.md) §8
for why it is deliberately not in CI.

> Point `TEST_DATABASE_URL` only at a disposable database. The invariant suite
> writes freely.

### Run the application

```bash
cd backend
export DATABASE_URL=jdbc:postgresql://localhost:5432/interview_platform
export DATABASE_USERNAME=interview
export DATABASE_PASSWORD=<local password>
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Then `GET http://localhost:8080/actuator/health`. In `local` the candidate API
under `/api/v1/interviews` is open (M5A development slice); everything else returns
`401` — deliberately: authentication arrives in M6, and until then the default
is closed.

### Run the candidate UI

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

The dev server proxies `/api` to the backend on :8080. Starting the backend with
the `local` profile seeds a realistic "Backend Engineer Interview" and a dev
candidate, so a fresh checkout has something to interview against.

Frontend commands: `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`.

### A disposable local PostgreSQL without admin rights

If PostgreSQL binaries are installed but you cannot create services, run a
throwaway cluster as your own user:

```bash
initdb -D .tools/pgdata -U postgres --pwfile=<file with a password> -E UTF8 --locale=C
pg_ctl -D .tools/pgdata -l .tools/pg.log -o "-p 5433 -c listen_addresses=localhost" start
createdb -h localhost -p 5433 -U postgres interview_platform_test
# ...
pg_ctl -D .tools/pgdata stop
```

`.tools/` is git-ignored.

## Environment variables

No secret is ever committed. See [`.env.example`](.env.example).

| Variable | Required | Purpose |
|---|---|---|
| `DATABASE_URL` | yes | JDBC URL |
| `DATABASE_USERNAME` | yes | Application DB user (DML on `app` only) |
| `DATABASE_PASSWORD` | yes | — |
| `DATABASE_POOL_SIZE` | no (10) | Small on purpose: a pooler sits in front |
| `APP_ENVIRONMENT` | no (`local`) | One of `local`, `test`, `staging`, `prod` |
| `APP_ENGINE_VERSION` | no (build version) | Stamped on every interview attempt |
| `SERVER_PORT` | no (8080) | — |
| `TEST_DATABASE_*` | no | Points the test suite at an existing database |
| `APP_AI_PROVIDER` | no (`deterministic`) | `deterministic` or `anthropic` |
| `ANTHROPIC_API_KEY` | only when `anthropic` | Never committed, never logged |
| `APP_AI_MODEL` | no (`claude-opus-5`) | Exact model id, never an alias |

The AI provider defaults to `deterministic` on purpose: building, testing and
starting the application must never require a paid credential, and a
misconfigured environment should fall back to something that cannot spend money
by accident. With `anthropic` selected, a missing key fails startup rather than
failing every evaluation an hour later.

Configuration is bound to a validated `@ConfigurationProperties` record, so a
missing or malformed value fails startup instead of surfacing as a `null` later.

## Project layout

```
backend/                 Spring Boot 3.3, Java 21, single Maven module
  src/main/java/com/aiinterview/interviewplatform/
    shared/              mechanics: errors, tracing, clock, ids, config, security, jobs
    identity/            users, roles
    candidate/           profiles
    question/            skills, question bank, rubrics, templates
    interview/           attempts, turns, answers
    evaluation/          rubric grading
    reporting/           score aggregation
    ai/                  AI gateway (the only code that may call a provider)
    admin/               cross-module read models
    devtools/            dev-only seeding; never active outside local
  src/main/resources/db/migration/   Flyway — the ONLY owner of the schema
frontend/                React 19 + TypeScript + Vite candidate UI
  src/api/               the only place an HTTP call is made
  src/state/             the UI state machine
  src/hooks/             orchestration and polling
  src/components/        one component per screen
docs/                    architecture and design record
```

Each module is split into `api` / `application` / `domain` / `infrastructure`
**only where a layer earns its place**. Boundaries are enforced by ArchUnit in
CI, not by convention.

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request. Two parallel jobs:

| Job | What it does |
|---|---|
| `secret-scan` | gitleaks (pinned image, full history, `--redact`) |
| `build` | Java 21 → `mvn clean verify` → guard assertions |

`mvn clean verify` is the whole gate. On the GitHub runner Docker is present, so
Testcontainers starts **`postgres:16-alpine`** — the production-matching version —
Flyway migrates it from empty, and Hibernate's `ddl-auto=validate` checks all 18
entity mappings against the result.

Three guards make the gate hard to bypass accidentally:

1. **Java version is asserted**, not just requested, so a cached or changed
   toolchain fails loudly instead of building on 17.
2. **`TEST_DATABASE_URL` must be unset.** That external-database escape hatch
   exists for workstations without a container runtime; in CI it would silently
   bypass the pinned image.
3. **Every required suite must have run.** `ArchitectureTest`,
   `MigrationAndMappingTest`, `DatabaseInvariantTest` and the evaluation and
   interview suites are each checked for a
   surefire report with a non-zero test count and zero failures, errors and
   skips — because a suite that stops running at all still produces a green
   `mvn verify`.

The pinned PostgreSQL image lives in `TestDatabase.IMAGE`; CI asserts the same
string appears in the build log, so the two cannot drift apart silently.

## Documentation

| Document | What it is |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Current system architecture |
| [docs/database.md](docs/database.md) | The 18-table schema and its invariants |
| [docs/development-plan.md](docs/development-plan.md) | Milestones and build order |
| [docs/m0-m1-implementation.md](docs/m0-m1-implementation.md) | Foundation and database baseline |
| [docs/m2-evaluation-engine.md](docs/m2-evaluation-engine.md) | The evaluation engine, scoring formula and provider port |
| [docs/m3-interview-engine.md](docs/m3-interview-engine.md) | The interview engine, sequencing, selection and follow-up graph |
| [docs/m4-worker-operations-catalogue.md](docs/m4-worker-operations-catalogue.md) | The job worker, operational visibility and catalogue authoring |
| [docs/m5-ai-provider-golden-set.md](docs/m5-ai-provider-golden-set.md) | The real AI provider, prompt versioning, golden set and agreement gate |
| [docs/m5a-http-api-candidate-ui.md](docs/m5a-http-api-candidate-ui.md) | The candidate HTTP API, the React UI, polling and the dev-only security scope |
| [docs/10-architecture-review.md](docs/10-architecture-review.md) | The decision record; authoritative over docs 01–09 |
| docs/01–09 | Original design package, superseded where it conflicts with doc 10 |

## Non-negotiables

These are locked decisions. Changing one requires revising
[docs/10-architecture-review.md](docs/10-architecture-review.md), not a pull
request.

1. **Flyway owns the schema.** `spring.jpa.hibernate.ddl-auto` is `validate` and
   never anything else.
2. **The backend computes every score.** The model supplies criterion verdicts
   and verbatim evidence; the arithmetic is deterministic Java.
3. **No AI call ever blocks a candidate.** Evaluation runs through a
   database-backed job queue, enqueued in the same transaction as the answer.
4. **Published content is immutable**, enforced by database triggers — so an
   interview completed today produces the same result tomorrow.
5. **Roles come from our `users` table**, never from a JWT claim.
6. **Idempotency comes from database constraints**, not a framework.
7. **Answer text, prompts, model output, emails and tokens are never logged.**
