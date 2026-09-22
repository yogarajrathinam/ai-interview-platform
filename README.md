# AI Interview Platform

AI-powered technical mock interviews with **rubric-anchored, evidence-backed
evaluation**. The differentiator is not that an AI asks questions — it is that
every score traces to a named rubric criterion and a quoted span of the
candidate's own answer, and stays reproducible years later.

> **Status: M0 + M1 complete.** The foundation and the database baseline exist
> and are verified. There is no business logic yet — no interview flow, no AI
> integration, no authentication, no frontend. See
> [docs/m0-m1-implementation.md](docs/m0-m1-implementation.md).

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

Then `GET http://localhost:8080/actuator/health`. Every other endpoint returns
`401` — deliberately: authentication arrives in M6, and until then the default
is closed.

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
  src/main/resources/db/migration/   Flyway — the ONLY owner of the schema
docs/                    architecture and design record
```

Each module is split into `api` / `application` / `domain` / `infrastructure`
**only where a layer earns its place**. Boundaries are enforced by ArchUnit in
CI, not by convention.

## Documentation

| Document | What it is |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Current system architecture |
| [docs/database.md](docs/database.md) | The 18-table schema and its invariants |
| [docs/development-plan.md](docs/development-plan.md) | Milestones and build order |
| [docs/m0-m1-implementation.md](docs/m0-m1-implementation.md) | What this milestone delivered |
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
