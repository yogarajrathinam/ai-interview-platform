# M0 + M1 — Implementation Record

> Foundation and database baseline. No business logic.
> Verified 2026-09-11 against PostgreSQL 18: `mvn clean verify` → **44/44 tests
> pass**, 2 migrations applied to an empty database, jar built.

## 1. What was implemented

### M0 — Spring Boot foundation

| Item | Detail |
|---|---|
| Runtime | Spring Boot 3.3.5, Java 21, Maven, single module |
| Dependencies | Web, Validation, Data JPA, Security, Actuator, Flyway, PostgreSQL, Testcontainers, JUnit 5, ArchUnit, java-uuid-generator |
| Error handling | `GlobalExceptionHandler` → RFC 9457 `ProblemDetail` with a stable `code` and `traceId`; stack traces never exposed |
| Error contract | `ErrorCode` enum — the full approved v1 set, so later milestones plug in rather than inventing codes |
| Tracing | `TraceIdFilter` → SLF4J MDC, `X-Trace-Id` header, every problem body. Honours inbound W3C `traceparent`, **validated** as 32 lowercase hex before use |
| Clock | `ClockConfig` supplies one injected `java.time.Clock` |
| Ids | `IdGenerator` port + `UuidV7IdGenerator` (time-ordered, keeps B-tree inserts local) |
| Configuration | `AppProperties` — a validated `@ConfigurationProperties` record; a bad value fails startup |
| Security | Default-deny stateless filter chain, CSRF off (no cookies), 401 rather than a login redirect. **No authentication mechanism** — that is M6 |

### M1 — Database

18 tables, 6 trigger functions, 21 triggers, and the full index set, in
`V1__baseline.sql`. `V2__seed_skills.sql` seeds JAVA, SPRING_BOOT, SQL.

18 JPA entities mapping the schema, validated by `ddl-auto=validate`.

## 2. Project structure

```
backend/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/aiinterview/interviewplatform/
    │   │   ├── InterviewPlatformApplication.java
    │   │   ├── shared/     config · error · id · jobs/domain · security · time · web
    │   │   ├── identity/domain/      UserEntity
    │   │   ├── candidate/domain/     ProfileEntity
    │   │   ├── question/domain/      Skill · Question · QuestionVersion · RubricCriterion
    │   │   │                         · InterviewTemplate · TemplateSkill · TemplateQuestionSlot
    │   │   ├── interview/domain/     Interview · InterviewQuestion · Answer
    │   │   ├── evaluation/domain/    Evaluation · EvaluationCriterionResult
    │   │   ├── reporting/domain/     InterviewReport · ReportSkillScore
    │   │   ├── ai/domain/            AiInvocation
    │   │   └── admin/                (package-info only — read models arrive in M9)
    │   └── resources/
    │       ├── application.yml, application-local.yml
    │       └── db/migration/V1__baseline.sql, V2__seed_skills.sql
    └── test/java/com/aiinterview/interviewplatform/
        ├── architecture/   ArchitectureTest · ModuleRules
        ├── database/       MigrationAndMappingTest · DatabaseInvariantTest
        └── support/        AbstractDatabaseTest · TestDatabase · SchemaFixtures
```

Every module carries a `package-info.java` stating its responsibility and its
boundary rule. Layers (`api` / `application` / `infrastructure`) are **not**
created until something occupies them — empty abstraction layers would be
ceremony.

### Two structural deviations, both deliberate

1. **An `ai` module was added** to the eight packages listed in the brief. The
   approved architecture requires an AI gateway module, and `ai_invocations`
   has no honest home in `evaluation` (it serves reporting too) or in `shared`
   (which holds no business data).
2. **Foreign keys are raw `UUID` columns, not `@ManyToOne` associations.** A
   JPA association from `interview.domain` to `question.domain` would be a
   compile-time dependency across a module boundary — exactly what the ArchUnit
   rules forbid. It also eliminates lazy-loading, cascade and N+1 hazards.

## 3. Migrations

| Version | File | Contents |
|---|---|---|
| 1 | `V1__baseline.sql` | Schema `app`, `pgcrypto`, 18 tables, all constraints and indexes, 6 trigger functions, 21 triggers |
| 2 | `V2__seed_skills.sql` | JAVA, SPRING_BOOT, SQL (idempotent via `ON CONFLICT`) |

Skills are reference data and belong in a migration. Questions and templates are
**content** and do not: they are loaded through the admin service so they pass
the same publish-gate triggers candidate-facing content does.

## 4. Database invariants

30 rules are enforced by PostgreSQL. See [database.md](database.md) §5 for the
full table. The mechanisms:

- **5 partial unique indexes** — one live attempt, one current evaluation, one
  current report, one published version per family (×2), one active job per key.
- **1 primary key doing idempotency work** — `answers PK = interview_question_id`.
- **6 trigger functions** — published-content immutability, publish gates,
  append-only history.
- **`ON DELETE RESTRICT`** on every catalogue reference from an attempt.
- **`CHECK` constraints** for shapes, ranges and enum values.

## 5. Test strategy

| Suite | Tests | Needs a database | Proves |
|---|---|---|---|
| `ArchitectureTest` | 13 | No | Module boundaries, layering, injected clock and ids, no field injection |
| `MigrationAndMappingTest` | 6 | Yes | Migrations apply from empty; **exactly 18 tables**; triggers and partial indexes installed; seed applied; `ddl-auto=validate` passed |
| `DatabaseInvariantTest` | 25 | Yes | The **database** rejects each violation |

All 44 pass.

Two design choices worth stating:

**Invariant tests write with plain JDBC**, never through JPA. The claim under
test is that PostgreSQL rejects the write — routing through Hibernate or Bean
Validation would risk proving only that Java rejected it first.

**No `@Transactional` on the invariant tests.** Statements autocommit, so a
rejected write cannot poison a surrounding transaction and each assertion
observes real engine behaviour.

Positive cases are tested alongside the negative ones — a constraint that
rejects everything is as broken as one that rejects nothing. Hence: a new
attempt *is* allowed once the previous completed; a superseded evaluation *may*
coexist with the current one; a published version *may* still be archived; a
well-formed follow-up *is* accepted; and completed work *can* be re-enqueued.

## 6. Commands

```bash
cd backend

mvn test -Pno-docker          # architecture tests only, no database
mvn test                      # everything, via Testcontainers
mvn clean verify              # full build + tests + jar
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Against an existing PostgreSQL instead of Testcontainers:

```bash
export TEST_DATABASE_URL=jdbc:postgresql://localhost:5433/interview_platform_test
export TEST_DATABASE_USERNAME=postgres
export TEST_DATABASE_PASSWORD=<local test password>
mvn test
```

## 7. Environment variables

Required to run: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`.
Optional: `DATABASE_POOL_SIZE`, `APP_ENVIRONMENT`, `APP_ENGINE_VERSION`,
`SERVER_PORT`, and the `TEST_DATABASE_*` trio. See `.env.example`. No secret is
committed; `.env` is git-ignored.

## 8. Problems discovered

### Corrections applied to the approved DDL

Each was surfaced rather than silently absorbed.

| # | Problem | Resolution |
|---|---|---|
| **FIX-1** | `uq_iq_position` carried a transcription artefact (`CHECK_PLACEHOLDER_REMOVED`) — invalid SQL | Corrected to `CONSTRAINT uq_iq_position UNIQUE (interview_id, position)` |
| **FIX-2** | **`citext` breaks `ddl-auto=validate`.** pgjdbc reports a `citext` column as `Types.OTHER`; Hibernate expects `VARCHAR` for a `String` field, so validation fails — blocking a stated Definition-of-Done item | `email text` + `CREATE UNIQUE INDEX uq_users_email ON app.users (lower(email))`. **The invariant is identical** (case-insensitive uniqueness) and the schema now needs one fewer extension |
| **FIX-3** | `ck_int_terminal` tied `completion_reason` to terminal states, but **every transition into `COMPLETING` already carries its reason** — the constraint made it impossible to record the reason when it is known | Split into `ck_int_terminal` (terminal ⇔ `completed_at`) and `ck_int_reason_when`. Invariant #24 preserved: a terminal attempt still requires both |
| **FIX-4** | `ck_ecr_span` allowed `evidence_start` set with `evidence_end` null (`NULL > x` is NULL, which passes a CHECK) | Added null-symmetry: `(evidence_start IS NULL) = (evidence_end IS NULL)` |
| **FIX-5** | Trigger firing order depended on the **incidental alphabetical order** of descriptive names — PostgreSQL fires BEFORE ROW triggers alphabetically | Numeric ordinals: `trg_qv_10_validate`, `trg_qv_20_guard`, `trg_*_90_touch` |

### Found while testing

**`ON DELETE RESTRICT` raises SQLSTATE `23001`, not `23503`.** The initial test
expected `foreign_key_violation`. PostgreSQL raises `restrict_violation` for an
explicit `RESTRICT` clause and reserves `23503` for the weaker `NO ACTION`
default. The test now asserts `23001`, which is strictly *more* precise: it
proves the stronger clause is genuinely in force rather than merely that some
foreign key exists.

### Environment obstacles

| Obstacle | Resolution |
|---|---|
| Java 17 installed; project needs 21 | Temurin JDK 21 unpacked into `.tools/` (git-ignored, no admin rights needed) |
| Maven absent | Maven 3.9.9 unpacked into `.tools/` |
| **No Docker, no WSL, no admin rights** — Testcontainers cannot run | `TestDatabase` resolves the instance: Testcontainers by default, or an external database via `TEST_DATABASE_URL`. Verified against a disposable PostgreSQL 18 cluster created with `initdb` in `.tools/` on port 5433 |

## 9. Known limitations

1. **Testcontainers itself is unexercised.** The suite ran against an external
   PostgreSQL because no container runtime exists on this workstation. The
   Testcontainers path is written and should be confirmed on the first machine
   or CI runner that has Docker.
2. **Verified on PostgreSQL 18, not 16.** The container image is pinned to
   `postgres:16-alpine` to match Supabase. Nothing used here is version-specific
   (partial indexes, `num_nonnulls`, plpgsql, `SKIP LOCKED` are all long
   established), but the 16 run is still outstanding.
3. **No CI pipeline yet.** The build is CI-ready — `mvn clean verify` is the
   whole gate — but no workflow file exists. It needs a runner with Docker.
4. **`admin` contains only a `package-info`.** Intentional: its read models
   arrive in M9.
5. **No repositories, services, controllers or DTOs.** Intentional: JPA exists
   at this milestone solely to validate the mapping.
6. **Security has no authentication mechanism.** Everything except
   `/actuator/health` returns 401. JWKS verification and role resolution are M6.
7. **Several ArchUnit rules currently match nothing** (controllers,
   repositories, `@Transactional` placement). Deliberate: they are tripwires
   installed before there is anything to violate them.
8. **Supabase projects are not provisioned**, and no AI provider account exists.
   Both are prerequisites for later milestones and are on the critical path for
   M6 and M2 respectively.
