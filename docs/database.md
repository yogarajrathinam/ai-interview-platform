# Database

> The implemented schema. **18 tables.** Supersedes `04-database.md`.
> Rationale for every decision: [10-architecture-review.md](10-architecture-review.md).
>
> Implemented by `V1__baseline.sql` and `V2__seed_skills.sql`, verified by 31
> tests against a real PostgreSQL.

## 1. The product rule the schema exists to serve

> An interview completed today must produce exactly the same result tomorrow,
> whatever changes to templates, questions, rubrics, prompts or evaluation
> configuration.

Four mechanisms, all in PostgreSQL — not in application code:

**1. Attempts pin versions, never families.** `interviews.template_id` points at
a template *version* row; `interview_questions.question_version_id` at a
question *version* row. No attempt row references a mutable "current" pointer.

**2. Pinned rows cannot be deleted.** Every foreign key from an attempt table
into the catalogue is `ON DELETE RESTRICT`. Verified: the attempt raises
SQLSTATE `23001` (`restrict_violation`) — specifically RESTRICT, not the weaker
`NO ACTION` default, which would raise `23503`.

**3. Pinned rows cannot be mutated.** Triggers reject any content change once
`status = 'PUBLISHED'`. The only permitted transition is `PUBLISHED → ARCHIVED`,
which hides content from *new* interviews without touching existing ones.

**4. The score recomputes from attempt-scoped tables alone.** Every input to the
arithmetic is snapshotted where it is used:

| Input | Snapshotted on |
|---|---|
| Criterion verdict, credit, weight | `evaluation_criterion_results` |
| Question weight, skill | `interview_questions` |
| Turn inclusion | `interview_questions.status` |
| Skill weight | `interview_templates` (pinned, immutable) + `report_skill_scores` |
| Scoring algorithm | `evaluations.evaluation_version` |
| Prompt, model, rubric | `evaluations.{prompt_version, provider, model, rubric_version}` |

That is why `interview_questions.skill_id` and `weight_bp` are denormalised:
archiving a question or refactoring the catalogue can never alter a past result.

**Reproducibility is not frozen results.** Re-evaluation is permitted and
append-only: a new row is inserted, the previous keeps its data with
`is_current = false`, and the old report is retained. Every historical state
stays queryable and attributed.

## 2. The 18 tables

| # | Table | Purpose | Mutability |
|---|---|---|---|
| 1 | `skills` | Taxonomy scores group by. Data, never a code enum | Mutable |
| 2 | `users` | Authoritative identity, role and status | Mutable |
| 3 | `profiles` | Candidate-supplied data; shared PK with `users` | Mutable |
| 4 | `questions` | Stable question *identity* across revisions | Mutable (status only) |
| 5 | `question_versions` | Question content | **Immutable once PUBLISHED** |
| 6 | `rubric_criteria` | The rubric, versioned with its question | **Frozen when parent leaves DRAFT** |
| 7 | `interview_templates` | Authored interview definition | **Immutable once PUBLISHED** |
| 8 | `template_skills` | Skill weights (composite PK) | Frozen with parent |
| 9 | `template_question_slots` | Ordered question plan | Frozen with parent |
| 10 | `interviews` | One candidate attempt | Mutable (state machine) |
| 11 | `interview_questions` | A turn | Mutable (turn state) |
| 12 | `answers` | Candidate's work; PK **is** the turn id | **Immutable** |
| 13 | `ai_invocations` | Every provider call, including failures | Write-once (+ payload purge) |
| 14 | `evaluations` | One terminal grading outcome | **Append-only** (`is_current` only) |
| 15 | `evaluation_criterion_results` | Per-criterion verdict + evidence | **Immutable** |
| 16 | `interview_reports` | Candidate-facing aggregate | **Append-only** (`is_current` only) |
| 17 | `report_skill_scores` | Per-skill breakdown | **Immutable** |
| 18 | `jobs` | Async work queue | Mutable (operational) |

### Removed, and why

| Table | Reason |
|---|---|
| `audit_log` | The schema's only **polymorphic foreign key**. Every Phase 0 auditable action is now attributed on the row it affected (`published_by`, `triggered_by`, `role_changed_by`, …) — no join, no `entity_type` dispatch |
| `organizations` | A concept with no behaviour. `ALTER TABLE ADD COLUMN <nullable>` is free in PostgreSQL 11+, so a nullable tenant column was never a seam worth pre-building |
| `follow_up_prompts` | Merged into `rubric_criteria.follow_up_prompt` — at most one probe per criterion |
| `answer_drafts` | localStorage covers refresh and browser crash, the real failure modes in an 18-minute session |
| `idempotency_keys` | Five natural constraints already provide idempotency (§4) |

Also considered and rejected: `rubric_versions`, `evaluation_runs`,
`interview_skill_weights`.

A test asserts the schema contains **exactly** these 18 tables, so
reintroducing one silently is not possible.

## 3. Versioning taxonomy

Only two things justify a version *entity*. The rest are strings on the
evaluation row.

| Version | Entity? | Pinned to the attempt? |
|---|---|---|
| Template version | **Yes** — `interview_templates` row | **Yes**, `interviews.template_id` |
| Question version | **Yes** — `question_versions` row | **Yes**, per turn |
| Rubric version | No — a rubric change *is* a content change | Transitively; recorded as `evaluations.rubric_version` |
| Evaluation version | No — a string | Recorded per evaluation |
| Prompt version | No — a versioned file in the repo | Recorded per evaluation |
| AI model / provider | No | Recorded per evaluation |

## 4. Idempotency: constraints, not a framework

| Duplicate risk | Constraint |
|---|---|
| Duplicate answer submission | `answers PRIMARY KEY (interview_question_id)` |
| Duplicate evaluation | `uq_evaluations_one_current ON evaluations(answer_id) WHERE is_current` |
| Duplicate live interview | `uq_interviews_one_live ON interviews(candidate_user_id) WHERE status IN ('IN_PROGRESS','COMPLETING')` |
| Duplicate report | `uq_reports_one_current ON interview_reports(interview_id) WHERE is_current` |
| Duplicate job | `uq_jobs_active_dedupe ON jobs(dedupe_key) WHERE status IN ('QUEUED','RUNNING')` |

The job index is **partial** on purpose. A full unique index would permanently
block re-enqueuing `EVALUATE_ANSWER:<id>` after the first job succeeded — which
would break admin re-evaluation. Both behaviours are covered by tests.

## 5. Database-enforced invariants

30 rules live in PostgreSQL. The 18 highest-value ones have a test proving the
*database* rejects the violation (not Java, not Bean Validation):

| Invariant | Mechanism | Tested |
|---|---|---|
| One live attempt per candidate | partial unique index | ✅ (+ allowed after completion) |
| One answer per turn | primary key | ✅ |
| One current evaluation per answer | partial unique index | ✅ (+ superseded rows allowed) |
| One current report per interview | partial unique index | — |
| One published version per question family | partial unique index | ✅ |
| One published version per template family | partial unique index | ✅ |
| Published question content immutable | `trg_qv_20_guard` | ✅ |
| Published rubric immutable | `trg_rc_20_guard` | ✅ |
| PUBLISHED → ARCHIVED still allowed | `trg_qv_20_guard` | ✅ |
| Answer content immutable | `trg_answers_20_immutable` | ✅ |
| Criterion result immutable | `trg_ecr_20_immutable` | ✅ |
| Evaluation append-only except `is_current` | `trg_eval_20_append_only` | ✅ |
| Template skill weights = 10000 bp to publish | `trg_tpl_10_validate` | ✅ |
| Template slot count matches declaration | `trg_tpl_10_validate` | ✅ |
| Rubric has 3–10 criteria to publish | `trg_qv_10_validate` | ✅ |
| Rubric criterion weights = 10000 bp | `trg_qv_10_validate` | ✅ |
| Pinned question version undeletable | `ON DELETE RESTRICT` | ✅ |
| Pinned template version undeletable | `ON DELETE RESTRICT` | ✅ |
| Follow-up turns carry zero weight | `ck_iq_shape` | ✅ (+ well-formed accepted) |
| Core turns carry no parent or prompt | `ck_iq_shape` | ✅ |
| Active job dedupe | partial unique index | ✅ (+ re-enqueue after success) |
| A RUNNING job names its owner | `ck_jobs_lock` | ✅ |

### Triggers

Six plpgsql functions. Guards fire on `UPDATE` (or `INSERT` for composition
children) only, so foreign-key cascades are unaffected.

| Function | Role |
|---|---|
| `guard_question_version` | Published question content is immutable |
| `validate_question_publish` | Publish gate: criterion count and weight sum |
| `guard_interview_template` | Published template config is immutable |
| `validate_template_publish` | Publish gate: weights, slot count, contiguity, skill coverage |
| `guard_rubric_criteria` / `guard_template_child` | Composition frozen when parent leaves DRAFT |
| `guard_append_only` / `guard_immutable_row` | Historical records |

Trigger names carry a numeric ordinal (`trg_qv_10_validate`,
`trg_qv_20_guard`, `trg_*_90_touch`) because PostgreSQL fires BEFORE ROW
triggers in **alphabetical order**; relying on the incidental ordering of
descriptive names is fragile.

A documented escape hatch exists for data-repair migrations only:

```sql
SET LOCAL app.allow_immutable_write = 'on';
```

## 6. Cascade behaviour

| Rule | Where | Why |
|---|---|---|
| `CASCADE` | profile, turns, answers, evaluations, criterion results, skill scores | The child is meaningless without its parent |
| `RESTRICT` | every catalogue reference, and `interviews.candidate_user_id` | Reproducibility; and deletion must be a deliberate two-step process, never an accidental cascade |
| `SET NULL` | `ai_invocations` correlation columns, all `*_by` attribution | Accounting and attribution survive content deletion |

## 7. Indexes

Every index serves a named query, or a foreign key participating in
`CASCADE`/`RESTRICT` — PostgreSQL does not index foreign keys automatically,
and an unindexed child FK turns every cascade into a sequential scan.

| Index | Query |
|---|---|
| `uq_users_auth` | Authentication lookup on **every** request |
| `uq_users_email` (functional, `lower(email)`) | Signup dedupe, admin search |
| `ix_qv_pool` (partial, PUBLISHED) | Pool selection at interview creation |
| `uq_iq_position` | The runner hot path — `GET /state` is one indexed scan |
| `ix_interviews_candidate` | History list |
| `ix_interviews_sweep` (partial, live) | MAINTENANCE expiry sweep |
| `ix_ai_user_day` | Pre-call budget check, on the hot path of every AI call |
| `ix_jobs_claim` (partial, QUEUED) | The claim query, every 500 ms |
| `ix_jobs_stale` (partial, RUNNING) | Stale-lock reaper |
| `ix_evaluations_answer`, `ix_ecr_criterion`, `ix_reports_interview`, `ix_rss_skill` | Cascade/RESTRICT checks and analytics |

Deliberately **not** created: any index on `skills` (8 rows),
`interview_templates.status`, `interviews.template_id`,
`interview_questions.parent_id`, `ai_invocations.status`, or full-text on
answers. Speculative indexes cost writes and buy nothing.

## 8. JSON policy

`jsonb` appears in exactly five columns, each justified:

| Column | Why JSON is right |
|---|---|
| `interview_reports.strengths` / `.improvements` / `.study_recommendations` | Pure display output, derived from relational rows, regenerable, never queried into |
| `ai_invocations.raw_response` | Provider-shaped, never queried by field |
| `jobs.payload` | Heterogeneous by job type |

**Nothing is ever queried out of a `jsonb` column.** Anything that needs
querying gets promoted to a real column in a migration. Criterion results in
particular are relational rows, not JSON — that would have been the single most
damaging shortcut available, blocking both score recomputation in SQL and all
cross-candidate gap analysis.

## 9. Conventions

- `timestamptz` everywhere, UTC. `updated_at` only on genuinely mutable tables.
- Enums are `text` + `CHECK`, never PostgreSQL `ENUM` types: adding or removing
  a value is an ordinary migration.
- Weights are integer **basis points** summing to 10000 — exact, with a
  checkable invariant. Never floats.
- Money is `bigint` micro-USD. Never a float.
- Primary keys are UUIDv7 generated by the application; `gen_random_uuid()` is a
  safety net for manual and seed inserts only.
- Own schema `app`, and **no foreign key into Supabase's `auth.*`** — the
  platform stays portable.
- No soft-delete columns; lifecycle is modelled with `status`.

## 10. Migrations

`V1__baseline.sql` (schema, indexes, triggers) and `V2__seed_skills.sql`
(JAVA, SPRING_BOOT, SQL).

Skills are reference data and belong in a migration. **Questions and templates
are content and do not**: they are loaded through the admin service so they pass
the same publish-gate triggers candidate-facing content does.

Rules: never edit an applied migration; every migration stays backwards
compatible for one release so a rollback needs no restore; migrations run as a
separate DDL-privileged user, while the application user holds DML on `app` only.
