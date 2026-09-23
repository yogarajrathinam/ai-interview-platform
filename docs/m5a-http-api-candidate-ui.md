# M5A — HTTP API + Candidate Interview UI (vertical slice)

**Status:** implemented, uncommitted
**Depends on:** M3 (interview engine), M4 (job worker), M4.5 (AI provider)
**Schema changes:** none
**Security posture:** development slice — **no authentication**. See §9.

The purpose is product validation, not architecture. Everything below exposes
behaviour that already existed; no interview rule, scoring rule or lifecycle
rule was changed to make the UI possible.

---

## 1. What the API turned out to be

The brief proposed six endpoints. Inspecting the existing contracts removed two
of them, because the engine already answered the question they would have asked.

| Proposed | Outcome |
|---|---|
| `POST /interviews` | Kept |
| `POST /interviews/{id}/start` | **Dropped.** An attempt is created *and* planned by one operation, and `InterviewStatus` deliberately has no `CREATED` state. A second call could only be a no-op. |
| `GET /interviews/{id}` | Kept — this is the polling endpoint |
| `GET /interviews/{id}/current-question` | **Dropped.** The current question is a field of the state every endpoint already returns. Fetching it separately would give a client two answers to "which question am I on" and lose the race on a refresh. |
| `POST /interviews/{id}/answers` | Kept |
| `GET /interviews/{id}/result` | Kept |
| — | `POST /interviews/{id}/advance` **added**: serving a question is a state change (§3) |
| — | `POST /interviews/{id}/completion` **added**: the candidate can finish early |
| — | `GET /interview-templates/{key}` **added**: the start screen needs a title before an attempt exists |

Base path is `/api/v1`, matching the existing convention and `AppProperties.Api`.

### Endpoints

```
GET  /api/v1/interview-templates/{templateKey}   → TemplateSummary
POST /api/v1/interviews                          → InterviewState      (200, idempotent)
GET  /api/v1/interviews/{id}                     → InterviewState      (poll here)
POST /api/v1/interviews/{id}/advance             → InterviewState
POST /api/v1/interviews/{id}/answers             → SubmitAnswerResponse
POST /api/v1/interviews/{id}/completion          → InterviewState
GET  /api/v1/interviews/{id}/result              → InterviewResult
```

`POST /interviews` answers **200, not 201**: the operation resumes an existing
live attempt when there is one, and claiming to have created something is a lie
a client could act on.

---

## 2. Contracts

**`InterviewState`** — the whole client-visible interview in one shape, returned
by every mutating endpoint so a client never needs a second call to learn what
its own request did.

```json
{
  "interviewId": "...", "status": "IN_PROGRESS", "completionReason": null,
  "startedAt": "...", "hardDeadlineAt": "...", "serverTime": "...",
  "progress": {
    "coreTotal": 6, "coreAnswered": 2, "followUpsAsked": 0,
    "followUpsRemaining": 3, "answeredTotal": 2, "evaluatedTotal": 1,
    "gradingSettled": false
  },
  "currentQuestion": {
    "interviewQuestionId": "...", "position": 3, "kind": "CORE",
    "isFollowUp": false, "parentTurnId": null, "skillId": "...",
    "promptText": "...", "contextText": null,
    "expectedDurationSec": 180, "askedAt": "..."
  },
  "timeline": [ { "interviewQuestionId": "...", "position": 1, "kind": "CORE",
                  "status": "EVALUATED", "skillId": "...", "parentTurnId": null } ]
}
```

**It carries no score, anywhere.** That is not an oversight: a candidate who can
see marks mid-interview tunes later answers to earlier feedback. An API test
asserts the substring `score` never appears in this response.

`gradingSettled` is derived server-side rather than left to the client to infer
from two counters — it is the single flag polling waits on.

**Submit** `POST /answers`:

```json
{ "interviewQuestionId": "...", "contentText": "...", "timeSpentSec": 42 }
→ { "interviewQuestionId": "...", "duplicate": false, "state": { ...InterviewState } }
```

**Result** `GET /result` — available only once the attempt stops accepting
answers. Overall score, per-skill scores, and per-question criterion verdicts
each with the verbatim `evidenceQuote` from the candidate's own answer.

---

## 3. Why `advance` exists

`start` plans the interview and the state immediately *names* the first turn —
but that turn is `PENDING` with `askedAt: null`. `advance` moves it to `ASKED`.

The `PENDING` / `ASKED` distinction is what makes "candidates abandon at
question five" answerable at all, and a client that rendered a question without
advancing would silently destroy that signal. It is a `POST` because it also
runs the follow-up phase when the core plan is exhausted and moves a finished
attempt toward completion — behind a `GET`, any proxy or prefetcher could
replay it.

It is idempotent: calling it twice returns the same turn.

---

## 4. Asynchronous evaluation and polling

Submitting an answer stores it and enqueues an `EVALUATE_ANSWER` job **in the
same transaction** (the existing transactional outbox). The M4 worker calls the
provider afterwards. No second async mechanism was introduced.

So the API does not block:

```
POST /answers        → 200 immediately, turn is ANSWERED
GET  /{id}  (poll)   → progress.gradingSettled === false
GET  /{id}  (poll)   → progress.gradingSettled === true
POST /{id}/advance   → next question, or a finished attempt
```

Client polling is every **1.2s**, stops on settled / finished / fatal error /
unmount, and cannot start twice (a ref guards the loop). It gives up after
**120s** with `AI_UNAVAILABLE` rather than spinning forever against a buried
job. **No WebSockets or SSE**, per the brief — realtime belongs to the voice
milestone.

---

## 5. Idempotency and duplicate submission

Nothing new was built. Every guarantee already existed and is simply exposed:

| Hazard | What makes it safe |
|---|---|
| Double-clicked **Start** | `uq_interviews_one_live` partial unique index; `start` resumes |
| Double-clicked **Submit** | `answers` primary key **is** the turn id; the API returns `duplicate: true` |
| Refresh mid-interview | State is derived from turn rows on every read; nothing is client-held |
| Retry after a lost response | The turn is named in the body, so a retry is recognisably the same submission |
| Two concurrent submits | The primary key arbitrates; a test runs two threads and asserts one row |
| Duplicate grading | `uq_jobs_active_dedupe`; a test asserts exactly one job for two submits |

A duplicate submit returns **200 with `duplicate: true`**, not an error. A
double-click is not a failure, and reporting one invites the client to retry
into a loop.

The **first answer wins** — a retry never overwrites text that may already have
been graded. Asserted directly.

---

## 6. Error contract

The existing RFC 9457 `application/problem+json` contract is used unchanged; no
new error format was invented. Every response carries a stable `code` and the
request's `traceId`, which is also on the `X-Trace-Id` response header so the
browser can display it.

| Condition | Status | `code` |
|---|---|---|
| Unknown interview / template | 404 | `NOT_FOUND` |
| Missing or invalid field, bad UUID | 422 | `VALIDATION_FAILED` |
| Unparseable body | 400 | `MALFORMED_REQUEST` |
| Answering a finished interview | 409 | `INTERVIEW_NOT_IN_PROGRESS` |
| Starting with a live attempt (non-resume) | 409 | `LIVE_ATTEMPT_EXISTS` |
| Result requested too early | 409 | `RESULT_NOT_AVAILABLE` |
| Anything unexpected | 500 | `INTERNAL_ERROR` |

Tests assert that responses contain no stack trace, no SQL and no
`org.springframework` internals. **A provider exception never reaches the
browser** — the evaluation module records a failed evaluation and the job
succeeds, so a model outage surfaces as an ungraded turn, never as an HTTP error
carrying vendor text.

The frontend maps every one of these codes to a specific sentence. A generic
"something went wrong" would mean the error contract was built for nothing.

---

## 7. Frontend

No frontend existed, so this establishes the stack: **React 19 + TypeScript +
Vite**, plain CSS with tokens on `:root`. No component library, no CSS framework
and no state-management library were introduced — for six screens, each would be
a dependency the project carries long after this slice is replaced.

```
frontend/src/
  api/client.ts        one fetch, ApiError, code → message table
  api/interviews.ts    one function per endpoint; components never see a URL
  api/types.ts         the wire contract, hand-written
  state/interviewMachine.ts   the UI state union + reducer
  hooks/useInterview.ts       all orchestration and polling
  components/          Start, Question, Evaluating, Loading, Completed, Result, ErrorPanel
```

### State model

A discriminated union, not booleans:

```
START → LOADING → QUESTION ⇄ SUBMITTING → EVALUATING → (QUESTION | COMPLETED) → RESULT
                                                                              ↘ ERROR
```

Four separate `loading`/`submitting`/`evaluating`/`completed` flags admit
sixteen combinations, most meaningless — "submitting and completed" has no
meaning but nothing would stop it rendering. The union admits only states that
exist, and each carries the data it needs, so there is no way to be in
`QUESTION` without a question. The backend stays authoritative for the
interview's real lifecycle; this is presentation state only.

`SUBMITTING` keeps the question on screen, locked, rather than swapping to a
spinner — a failed submission must not discard the candidate's words.

---

## 8. Known limitations

- **No authentication.** The candidate id is sent by the client (§9).
- **Polling, not push.** Deliberate; realtime is a later milestone.
- **The result is computed, not stored.** `InterviewResult` is a read-only
  projection assembled from stored verdicts. It is *not* the M5 report: no
  persistence, no bands, no narrative, no versioning. Writing rows here would
  commit M5's design before it is made.
- **One template, one candidate in dev.** Seeded by `DevDataSeeder`.
- **No resume UI.** The API resumes a live attempt, but the app always starts
  from the landing screen.
- **Skip is not exposed.** `InterviewService.skip` exists; no screen uses it.

---

## 9. Security scope — read this before deploying anything

This milestone deliberately ships **no authentication**, per the brief. The
controls that exist:

- The candidate API is reachable **only when `app.environment` is `local` or
  `test`**. `SecurityConfig` adds the permit rules inside that branch; in
  staging or production the paths fall through to `anyRequest().authenticated()`
  and answer 401. This is enforced in code, not left to a reviewer to notice,
  and the application logs a warning at startup whenever the API is open.
- CORS allows exactly `http://localhost:5173`, and only in those environments.
  No wildcard origin exists to follow the config somewhere it matters.
- No secret was added. The frontend holds a candidate UUID and a template key —
  an identifier and a content key, neither of which is a credential.
- Provider credentials are untouched and unreachable from this layer; an
  ArchUnit rule confines the vendor SDK to one adapter package.
- Errors expose no stack traces, SQL or provider messages.

**This is a development/test vertical slice, not a production posture.**
Authentication, authorisation and ownership checks remain M6.

---

## 10. Architecture rules added

- `controllers_call_only_published_contracts` — a controller may not name an
  `application` or `domain` type. Without it, "thin controller" is a convention
  that erodes one convenience at a time.
- `controllers_do_not_compute_scores` — no dependency on a `Scorer` or
  `ScoringPolicy`. A controller that could reach the scorer could round it "just
  for display", and the number shown would stop being the number stored.
- `entities_are_not_exposed_by_controllers` — **fixed**. It matched any class
  whose name ended in `Entity` and, the first time a controller existed, flagged
  Spring's `ResponseEntity`. It now matches the `@Entity` annotation. A rule that
  fires on a naming coincidence teaches people to work around it.

Existing rules that now police real classes rather than standing empty:
`controllers_do_not_depend_on_repositories`, `api_does_not_reach_into_infrastructure`,
`transactions_are_declared_in_application_services`.

---

## 11. Manual test procedure

```bash
# 1. A disposable PostgreSQL (see README) on :5433, database interview_platform

# 2. Backend — seeds a realistic template on first run
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 3. Frontend
cd frontend
npm install
npm run dev            # http://localhost:5173

# 4. Open http://localhost:5173 and run the interview.
```

To grade with the **real** provider rather than the deterministic one:

```bash
export APP_AI_PROVIDER=anthropic
export ANTHROPIC_API_KEY=...        # never committed, never logged
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

What to verify: the interview starts; a real question appears; an answer is
accepted immediately; the evaluating state appears; the next question or a
follow-up arrives; the interview completes; the result shows a backend score
with criterion verdicts and quoted evidence. Confirm the provider was actually
called by checking `app.ai_invocations` — it records provider, model and prompt
version per call.

The automated suite continues to use the deterministic provider; only this
manual run exercises the paid one.
