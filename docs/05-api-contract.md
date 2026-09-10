# 05 — API Contract (v1)

> Status: Draft for approval · Depends on: 01, 02, 04

## 1. Review of the proposed API, and what changed

The endpoint list in the brief is a good starting point. Six changes are proposed,
each with a reason:

| # | Change | Reason |
|---|---|---|
| 1 | **Add `GET /interviews/{id}/state`** as the single source of truth for the runner | Without it the client must stitch together several responses and hold progression state — the exact bug class described in 02 §1. One endpoint, one shape, refresh-safe |
| 2 | **Add `POST /interviews/{id}/advance`** | Progression must be an explicit, idempotent server operation, and it is the natural place to wait for a follow-up decision. Returning the next question from the answer response conflates two concerns and breaks retry safety |
| 3 | **`POST /answers` returns `202`, not the evaluation** | Evaluation is asynchronous (01 §6.4). A `200` with a score would tie an HTTP thread to a model call |
| 4 | **Answers are addressed by turn**: body carries `interviewQuestionId` | Positional or implicit "current question" submission is unsafe with retries, two tabs, and back buttons |
| 5 | **Rename attempt resources in the UI/URLs as `attempts`, keep `/interviews` in the API** | Kept as `/interviews` for the brief's continuity; the docs consistently call the entity an *attempt* to avoid the template/attempt confusion |
| 6 | **Admin under `/api/v1/admin/**`** with its own auth rule | A single misconfigured filter shouldn't expose admin data on a candidate path. Path separation makes the security rule trivially auditable |

Also added: RFC 9457 problem details, cursor pagination, idempotency keys, explicit
concurrency semantics, and rate-limit headers.

## 2. Conventions

### 2.1 Base and versioning

- Base path `\/api\/v1`. Version is in the path (proxy-friendly, cache-friendly,
  obvious in logs). **v1 is additive-only**: new optional fields and new endpoints
  are non-breaking; removing or retyping a field requires `/api/v2`.
- Clients must ignore unknown fields. Documented in the OpenAPI description.
- `Deprecation` and `Sunset` headers announce retirement (RFC 8594) — no silent removals.

### 2.2 Media types, casing, formats

- `application/json; charset=utf-8`; errors use `application/problem+json`.
- `camelCase` JSON. `snake_case` stays in the database; the mapping happens in DTOs.
- Timestamps RFC 3339 UTC with `Z` (`2026-09-10T09:31:04Z`). Durations in explicit
  units in the field name (`durationSec`, `retryAfterMs`).
- IDs are UUID strings. Scores are JSON numbers with 2 decimals (`7.25`); weights are
  integers in basis points (`2500` = 25%) — never floats.
- Enums are `SCREAMING_SNAKE_CASE` strings, never ordinals.

### 2.3 Response envelopes

Single resource → the resource object at the top level. **Collections → an envelope**:

```json
{
  "data": [ /* items */ ],
  "page": { "nextCursor": "eyJpZCI6…", "limit": 25, "hasMore": true }
}
```

Reason: single resources with a `data` wrapper add noise to every client; collections
genuinely need out-of-band pagination metadata. Consistency is preserved by making
the rule mechanical ("arrays are always enveloped").

### 2.4 Pagination

Cursor-based (`?limit=25&cursor=…`), opaque base64 cursor over `(created_at, id)`.
`limit` max 100, default 25. Total counts are **not** returned by default (they force
a second expensive query); admin list endpoints accept `?includeTotal=true` where a
count genuinely matters.

### 2.5 Errors — RFC 9457 Problem Details

Every non-2xx response, without exception:

```json
{
  "type": "https://api.<domain>/problems/interview-not-in-progress",
  "title": "Interview is not in progress",
  "status": 409,
  "detail": "Interview 8f2a… has status COMPLETED and cannot accept answers.",
  "instance": "/api/v1/interviews/8f2a…/answers",
  "code": "INTERVIEW_NOT_IN_PROGRESS",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "errors": [
    { "field": "contentText", "code": "TOO_SHORT", "message": "Must be at least 20 characters." }
  ]
}
```

- `code` is the **stable machine contract** (clients branch on this, never on `title`
  or `status` alone).
- `traceId` is the W3C trace id, also present in logs — a user can paste it into
  support and we find the request.
- `errors[]` appears only for validation failures.
- `detail` is safe for display; it never contains internal identifiers of other
  users, SQL, stack traces, or provider messages.

Canonical error codes: `VALIDATION_FAILED`, `UNAUTHENTICATED`, `TOKEN_EXPIRED`,
`FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `INTERVIEW_NOT_IN_PROGRESS`,
`INTERVIEW_EXPIRED`, `ANSWER_ALREADY_SUBMITTED`, `LIVE_ATTEMPT_EXISTS`,
`IDEMPOTENCY_KEY_REUSED`, `RATE_LIMITED`, `AI_UNAVAILABLE`, `REPORT_NOT_READY`,
`TEMPLATE_NOT_PUBLISHED`, `INTERNAL_ERROR`.

### 2.6 Status code policy

| Code | Used for |
|---|---|
| 200 | Successful read / synchronous mutation |
| 201 | Resource created (`Location` header set) |
| 202 | Accepted; work continues asynchronously (answer submit, complete, not-ready result) |
| 204 | Successful delete / no body |
| 400 | Malformed request |
| 401 | Missing/invalid/expired token |
| 403 | Authenticated but not permitted (role failures only) |
| 404 | Not found **or not visible to this caller** (see §2.8) |
| 409 | State conflict (wrong interview status, duplicate live attempt) |
| 410 | Interview expired |
| 422 | Semantically invalid (validation) — `VALIDATION_FAILED` |
| 429 | Rate limited (`Retry-After` header) |
| 503 | Dependency unavailable and the request cannot be queued |

We use `422` for field validation and reserve `400` for malformed syntax; this lets
clients distinguish "your JSON is broken" from "your data is wrong".

### 2.7 Idempotency

- **Required** on `POST /interviews` and `POST /interviews/{id}/complete` via the
  `Idempotency-Key` header (client-generated UUID). Replays within 24 h return the
  original response. Same key + different body → `409 IDEMPOTENCY_KEY_REUSED`.
- `POST /interviews/{id}/answers` uses a body field `clientSubmissionId` instead,
  because the natural uniqueness scope is the turn, not the request (04 §3.5). A
  replay returns `202` with `duplicate: true` and the original `answerId`.
- `POST /interviews/{id}/advance` and `/start` are naturally idempotent: calling them
  in a settled state returns the current state rather than erroring.

### 2.8 Authorisation and existence disclosure

- All `/api/v1/**` except `/health`, `/api/v1/public/**` require
  `Authorization: Bearer <supabase access token>`.
- Ownership: a candidate may only address their own interviews. A request for
  someone else's returns **`404`, not `403`** — `403` confirms the resource exists.
  `403` is reserved for role violations on a resource whose existence isn't secret
  (e.g. a candidate hitting `/admin/**`).

### 2.9 Rate limits

Returned on every response: `RateLimit-Limit`, `RateLimit-Remaining`,
`RateLimit-Reset`; `Retry-After` on 429.

| Bucket | Limit |
|---|---|
| Auth-adjacent (`/me` bootstrap) | 60 / min / user |
| `POST /interviews` | 10 / hour / user |
| `POST …/answers` | 30 / min / user |
| `POST …/advance` | 60 / min / interview |
| `GET …/state`, `GET …/result` (polling) | 120 / min / interview |
| Admin writes | 120 / min / user |
| Global per IP (unauthenticated) | 100 / min |

### 2.10 Caching and concurrency

- Reads of published templates: `Cache-Control: private, max-age=60` + `ETag`.
- Interview state: `no-store`.
- Admin mutations on templates/questions use `If-Match` with the resource `ETag`
  (optimistic concurrency) — two admins editing the same template must not clobber
  each other silently.

## 3. Candidate API

### 3.1 Identity

```
GET   /api/v1/me
PATCH /api/v1/me/profile
```

`GET /me` performs just-in-time provisioning (02 §3.1) and returns:

```json
{
  "userId": "…", "email": "a@b.com", "displayName": "Asha",
  "role": "CANDIDATE", "profileComplete": false,
  "liveInterview": { "interviewId": "…", "status": "IN_PROGRESS", "templateTitle": "Java Backend Fundamentals" }
}
```

`liveInterview` is included so the dashboard can show "Resume interview" without a
second call — the one deliberate denormalisation in the API.

### 3.2 Templates

```
GET /api/v1/interview-templates?skill=JAVA&level=MID&limit=25&cursor=…
GET /api/v1/interview-templates/{templateId}
```

Only `PUBLISHED` + visible templates. List item:

```json
{
  "id": "…", "templateKey": "java-backend-fundamentals", "version": 3,
  "title": "Java Backend Fundamentals", "level": "MID",
  "summary": "Core Java, Spring Boot and SQL for backend roles.",
  "questionCount": 8, "targetDurationMin": 18,
  "skills": [ { "code": "JAVA", "name": "Java", "weightBp": 4000 },
              { "code": "SPRING_BOOT", "name": "Spring Boot", "weightBp": 3500 },
              { "code": "SQL", "name": "SQL", "weightBp": 2500 } ],
  "attemptSummary": { "attemptCount": 2, "bestScore": 6.8, "lastAttemptAt": "2026-08-30T10:12:00Z" }
}
```

Detail adds `description`, `instructions`, `maxFollowUpsTotal`, `hardDurationMin`,
`passingScore`. **It never exposes the question list** — that would leak the bank.

### 3.3 Interview lifecycle

```
POST /api/v1/interviews                      (Idempotency-Key required)
GET  /api/v1/interviews?status=COMPLETED&limit=25&cursor=…
GET  /api/v1/interviews/{id}
POST /api/v1/interviews/{id}/start
GET  /api/v1/interviews/{id}/state
POST /api/v1/interviews/{id}/answers
PUT  /api/v1/interviews/{id}/questions/{interviewQuestionId}/draft
POST /api/v1/interviews/{id}/questions/{interviewQuestionId}/skip
POST /api/v1/interviews/{id}/advance
POST /api/v1/interviews/{id}/complete         (Idempotency-Key required)
POST /api/v1/interviews/{id}/abandon
GET  /api/v1/interviews/{id}/result
```

**`POST /interviews`** — `{ "templateId": "…" }` → `201`, `Location`, body
`{ interviewId, status: "CREATED", templateVersion, questionCount, hardDurationMin }`.
Errors: `409 LIVE_ATTEMPT_EXISTS` (with the existing `interviewId` in `detail`),
`409 TEMPLATE_NOT_PUBLISHED`, `429`.

Creation resolves the question plan (POOL slots → concrete `question_version_id`s)
and writes `interview_questions` in `PENDING`. Doing this at create rather than start
means the "Begin" click is instant and the transaction is retry-safe.

**`POST /{id}/start`** → `200 InterviewState`; sets `started_at` and
`hard_deadline_at`. Calling it when already `IN_PROGRESS` returns current state
(idempotent). Calling it on a terminal attempt → `409`.

**`GET /{id}/state`** — the runner's only read. This is the most important response
shape in the product:

```json
{
  "interviewId": "…",
  "status": "IN_PROGRESS",
  "startedAt": "2026-09-10T09:00:00Z",
  "hardDeadlineAt": "2026-09-10T09:45:00Z",
  "serverTime": "2026-09-10T09:07:12Z",
  "progress": {
    "coreTotal": 8, "coreAnswered": 3,
    "followUpsUsed": 1, "followUpsRemaining": 3,
    "answeredTotal": 4
  },
  "currentQuestion": {
    "interviewQuestionId": "…",
    "position": 5,
    "kind": "FOLLOW_UP",
    "parentInterviewQuestionId": "…",
    "skill": { "code": "JAVA", "name": "Java" },
    "difficulty": "MEDIUM",
    "questionType": "CONCEPTUAL",
    "promptText": "You mentioned ConcurrentHashMap uses locking — which part of the map is locked, and what changed in Java 8?",
    "contextText": null,
    "expectedDurationSec": 120,
    "draftText": "…restored draft…",
    "askedAt": "2026-09-10T09:06:40Z"
  },
  "timeline": [
    { "interviewQuestionId": "…", "position": 1, "kind": "CORE", "status": "EVALUATED", "skillCode": "JAVA" }
  ]
}
```

`serverTime` is included so the client renders the countdown from server time and a
skewed device clock can't create a phantom expiry. `timeline` carries no answer or
score content — the runner must not be able to show scores mid-interview.

**`PUT …/draft`** — `{ "contentText": "…" }` → `204`. Debounced ~3 s by the client.
Deliberately not `PATCH`: it's a whole-value replace. Drafts are never evaluated.

**`POST /{id}/answers`**

```json
{ "interviewQuestionId": "…", "contentText": "…", "timeSpentSec": 142,
  "clientSubmissionId": "1f0b…", "inputMode": "TEXT" }
```

→ `202`

```json
{ "answerId": "…", "interviewQuestionId": "…", "evaluationStatus": "PENDING",
  "duplicate": false, "advanceHintMs": 1200 }
```

`inputMode` is in the contract from day one so voice needs no v2 (it will add
`mediaUploadUrl` and `transcript*` fields — additive).
Errors: `409 INTERVIEW_NOT_IN_PROGRESS`, `410 INTERVIEW_EXPIRED`,
`409 ANSWER_ALREADY_SUBMITTED` (only if `clientSubmissionId` differs from the stored
one — same id is a `202` replay), `422 VALIDATION_FAILED`.

**`POST /{id}/advance`** → either

```json
{ "status": "READY", "state": { /* full InterviewState */ } }
```

or, while a follow-up decision is still pending:

```json
{ "status": "PENDING", "reason": "AWAITING_EVALUATION", "retryAfterMs": 1500 }
```

with HTTP `202`. If evaluation exceeds the follow-up decision budget (default 10 s),
the engine gives up on the follow-up, proceeds to the next core question, and
`advance` returns `READY` — **the interview never stalls on the AI**. This fallback is
the single most important resilience rule in the runner.

**`POST /{id}/complete`** → `202 { status: "COMPLETING", evaluated: 6, total: 8 }`.
Allowed from `IN_PROGRESS` at any point (finish early).

**`GET /{id}/result`** → `202` with progress while `COMPLETING`, `200` with the report
when ready, `409 REPORT_NOT_READY` if the attempt was abandoned before any answer.

```json
{
  "interviewId": "…", "status": "COMPLETED",
  "template": { "title": "Java Backend Fundamentals", "version": 3 },
  "attemptNumber": 2,
  "completedAt": "2026-09-10T09:22:31Z", "durationSec": 1351,
  "overallScore": 7.25, "band": "SOLID", "passingScore": 6.0, "passed": true,
  "coverage": { "evaluatedWeightBp": 9000, "totalWeightBp": 10000, "questionsFailed": 1 },
  "skillScores": [
    { "skillCode": "JAVA", "name": "Java", "score": 8.10, "weightBp": 4000, "questionCount": 3, "coverageBp": 10000 }
  ],
  "summary": "Strong on collections and JVM memory; gaps in transaction isolation.",
  "strengths":    [ { "title": "Explains HashMap resizing precisely", "detail": "…", "skillCode": "JAVA", "interviewQuestionId": "…" } ],
  "improvements": [ { "title": "Transaction isolation levels", "detail": "…", "skillCode": "SQL", "criterionCode": "ISOLATION_LEVELS" } ],
  "studyRecommendations": [ { "skillCode": "SQL", "title": "Read committed vs repeatable read", "detail": "…" } ],
  "questions": [
    {
      "interviewQuestionId": "…", "position": 1, "kind": "CORE",
      "skillCode": "JAVA", "promptText": "…", "answerText": "…",
      "status": "EVALUATED", "score": 8.0, "weightBp": 1250,
      "criteria": [
        { "code": "THREAD_SAFETY", "label": "HashMap is not thread-safe",
          "verdict": "MET", "weightBp": 3000,
          "evidence": { "quote": "HashMap isn't synchronised so concurrent puts can corrupt the table",
                        "start": 42, "end": 118 } },
        { "code": "NULL_HANDLING", "label": "ConcurrentHashMap rejects null keys/values",
          "verdict": "MISSING", "weightBp": 1500, "evidence": null,
          "comment": "Not addressed in the answer." }
      ]
    }
  ]
}
```

Note what is **absent** from the candidate response: confidence numbers, model ids,
prompt versions, token counts, raw model output. Those are admin-only (03 §4.2).

### 3.4 History

`GET /api/v1/interviews?status=COMPLETED` returns compact rows
(`id, templateTitle, attemptNumber, completedAt, overallScore, band, durationSec,
topSkill, weakestSkill`) — enough for the history table without fetching reports.

## 4. Admin API

All under `/api/v1/admin/**`, all requiring `role = ADMIN` resolved from **our**
`users` table.

### 4.1 Overview and people

```
GET /api/v1/admin/overview
GET /api/v1/admin/candidates?query=&limit=&cursor=
GET /api/v1/admin/candidates/{userId}
GET /api/v1/admin/candidates/{userId}/interviews
```

`overview`: `{ candidates, interviewsByStatus{}, evaluationsPending, evaluationsFailed24h, aiCost24hMicros, avgScore30d }`.

### 4.2 Attempt inspection

```
GET  /api/v1/admin/interviews?status=&templateId=&candidateId=&from=&to=
GET  /api/v1/admin/interviews/{id}                 -- full detail incl. timeline + provenance
GET  /api/v1/admin/interviews/{id}/answers/{answerId}/evaluations
POST /api/v1/admin/answers/{answerId}/re-evaluate  -- append-only re-run, audited
POST /api/v1/admin/interviews/{id}/regenerate-report
POST /api/v1/admin/interviews/{id}/abandon
```

The admin interview detail is the only place `aiInvocation` provenance, criterion
`confidence`, and `modelReportedScore` vs `derivedScore` divergence are exposed.

### 4.3 Question bank

```
GET    /api/v1/admin/questions?skill=&difficulty=&status=&query=
POST   /api/v1/admin/questions                     -- creates family + v1 DRAFT
GET    /api/v1/admin/questions/{questionId}
GET    /api/v1/admin/questions/{questionId}/versions
POST   /api/v1/admin/questions/{questionId}/versions        -- new DRAFT from latest
PATCH  /api/v1/admin/question-versions/{versionId}          -- DRAFT only, If-Match
PUT    /api/v1/admin/question-versions/{versionId}/criteria -- whole rubric replace, DRAFT only
POST   /api/v1/admin/question-versions/{versionId}/publish
POST   /api/v1/admin/question-versions/{versionId}/archive
POST   /api/v1/admin/question-versions/{versionId}/dry-run-evaluation
```

`PATCH` on a `PUBLISHED` version → `409 CONFLICT` with
`detail: "Published question versions are immutable. Create version N+1."`

`dry-run-evaluation` takes a sample answer and returns the full evaluation **without
persisting anything**. This is the tool that makes rubric authoring tractable — it is
not optional polish; without it admins write rubrics blind.

### 4.4 Templates

```
GET    /api/v1/admin/interview-templates
POST   /api/v1/admin/interview-templates                    -- family + v1 DRAFT
GET    /api/v1/admin/interview-templates/{id}
PATCH  /api/v1/admin/interview-templates/{id}               -- DRAFT only, If-Match
PUT    /api/v1/admin/interview-templates/{id}/skills        -- whole-set replace
PUT    /api/v1/admin/interview-templates/{id}/slots         -- whole-set replace
POST   /api/v1/admin/interview-templates/{id}/versions      -- new DRAFT from published
POST   /api/v1/admin/interview-templates/{id}/publish
POST   /api/v1/admin/interview-templates/{id}/archive
POST   /api/v1/admin/interview-templates/{id}/preview-plan  -- resolve a sample plan
```

Whole-set `PUT` for skills and slots rather than per-row CRUD: these are small,
order-sensitive collections with cross-row invariants (weights sum to 10000, positions
contiguous). Validating a whole set once is far simpler and safer than validating
after each of six row operations.

`publish` returns `422` with a structured `errors[]` listing every violated invariant
at once, not the first one.

### 4.5 Skills and operations

```
GET  /api/v1/admin/skills
POST /api/v1/admin/skills
GET  /api/v1/admin/jobs?status=FAILED         -- job queue visibility
POST /api/v1/admin/jobs/{id}/retry
GET  /api/v1/admin/ai-usage?from=&to=&groupBy=day|model|purpose
```

Job visibility in admin is a Phase 0 requirement, not an ops luxury: when an
evaluation fails, someone must be able to see it and re-drive it without a psql session.

## 5. Cross-cutting

### 5.1 Health and metadata

```
GET /health/liveness      -- process is up
GET /health/readiness     -- DB reachable, migrations applied, AI provider probe (cached 30s)
GET /api/v1/meta/version  -- build sha, engine versions, prompt version (authenticated)
```

The AI probe in readiness must be *cached and non-blocking* — a provider blip must not
take the app out of the load balancer, because the app is still fully useful (answers
are queued).

### 5.2 CORS

Allow-list of exact origins from configuration (`app.cors.allowed-origins`), never
`*` with credentials. `Authorization`, `Content-Type`, `Idempotency-Key`, `If-Match`,
`traceparent` in allowed headers; `RateLimit-*`, `Location`, `ETag`, `Retry-After`
in exposed headers.

### 5.3 OpenAPI

`springdoc-openapi` generates the spec from annotated controllers and DTOs; the spec
is exported in CI to `docs/generated/openapi.json` and diffed against the previous
release to catch accidental breaking changes. The frontend generates types from it
(Phase 1: `orval`; Phase 0: hand-written Zod schemas that a contract test validates
against the spec).

### 5.4 Trace propagation

`traceparent` accepted and propagated; `traceId` echoed in every problem response and
in the `X-Trace-Id` response header on all responses.

## 6. Assumptions

- Single API surface for both web clients; no BFF, no GraphQL.
- Polling is the async transport in Phase 0 (02 §3.2); adding SSE later is additive
  (`GET /interviews/{id}/events`), and `advance` remains the fallback path.
- Supabase issues the access token; the API never issues its own session cookie.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Clients depend on `advance` returning `READY` quickly | 10 s follow-up decision budget with guaranteed progression |
| Polling costs at scale | Server-dictated `retryAfterMs`, rate limits, and an SSE upgrade path that needs no contract change |
| Admin API accidentally reachable by candidates | Path prefix + a dedicated security rule + an integration test asserting `403` for every admin route with a candidate token |
| v1 ossifies around a bad shape | `state` and `result` are the two shapes worth getting right now; everything else is additive |
| Over-fetching in `result` (whole report incl. answers) | Acceptable at ≤ 12 questions; `?include=summary` variant available if payloads grow |
