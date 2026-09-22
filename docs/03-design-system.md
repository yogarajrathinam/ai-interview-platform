# 03 — Design System

> Status: Draft for approval · Depends on: 01, 02
> **REVISED BY [10-architecture-review.md](10-architecture-review.md).** Where this document and doc 10 conflict, **doc 10 wins** — read doc 10 before implementing anything from this file.

## 1. Reasoning and design intent

The product must read as a **professional developer/SaaS tool** — the reference
points are Linear, Vercel dashboard, Stripe dashboard, GitHub: dense but calm,
strong typographic hierarchy, restrained colour used to mean something, generous
whitespace, no decoration that isn't information.

Explicitly rejected: gradient hero blobs, glassmorphism, animated particles, purple
"AI" branding, emoji as UI, celebratory confetti on results. Our credibility rests on
looking like an assessment instrument, not a toy. A candidate receiving a score of 5.2
must believe the number.

**Tokens first, components second.** Every visual value is a token in the MUI theme;
no component defines a raw hex, px radius, or font size. This is what makes a later
white-label for institutes (risk: "our college colours") a config change.

## 2. Foundations

### 2.1 Colour

Three roles only: **neutral** (95% of the UI), **brand** (actions and identity),
**semantic** (state). Plus a dedicated **score scale** — score colour is data
visualisation and must not be reused for UI state.

```
Neutral (slate)
  neutral/0    #ffffff    surface base (light)
  neutral/25   #fcfcfd    app background
  neutral/50   #f8fafc    subtle surface, table header, hover
  neutral/100  #f1f5f9    borders-subtle, skeleton base
  neutral/200  #e2e8f0    border default
  neutral/300  #cbd5e1    border strong, disabled text on dark
  neutral/400  #94a3b8    placeholder, icon muted
  neutral/500  #64748b    text tertiary
  neutral/600  #475569    text secondary
  neutral/700  #334155    text body strong
  neutral/800  #1e293b    text primary
  neutral/900  #0f172a    headings, dark surface

Brand (indigo — professional, distinct from success/error, WCAG-safe on white)
  brand/50   #eef2ff   tint surface (selected row, info-ish chip)
  brand/100  #e0e7ff   tint border
  brand/300  #a5b4fc   focus ring outer
  brand/500  #4f46e5   PRIMARY  (contrast 7.0:1 on white)
  brand/600  #4338ca   hover
  brand/700  #3730a3   active/pressed
  brand/900  #312e81   on-brand headings

Semantic
  success/50 #ecfdf5  success/500 #059669  success/700 #047857
  warning/50 #fffbeb  warning/500 #d97706  warning/700 #b45309
  danger/50  #fef2f2  danger/500  #dc2626  danger/700  #b91c1c
  info/50    #eff6ff  info/500    #2563eb  info/700    #1d4ed8

Score scale (0–10, used ONLY for scores/rubric verdicts)
  score/critical  0.0–3.9   #b91c1c
  score/low       4.0–5.4   #c2410c
  score/fair      5.5–6.9   #a16207
  score/good      7.0–8.4   #15803d
  score/strong    8.5–10.0  #047857
```

Rules:

- Semantic colour never carries meaning alone — always paired with an icon and text
  (colour-blind accessibility, and it survives greyscale printing of a report).
- Score colours are deliberately desaturated/darker: a report full of bright green
  and red looks like a game. Text on score chips is always the `700` weight on a `50`
  tint background.
- Dark mode: **out of scope for Phase 0**, but all colours are declared as CSS
  variables via the MUI `colorSchemes` mechanism so it is a token file, not a rewrite.

### 2.2 Typography

System font stack — no webfont download, no layout shift, native feel:

```
sans: 'Inter var', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif
mono: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace
```

Inter is loaded self-hosted (`font-display: swap`) if available, otherwise the stack
degrades cleanly.

| Token | Size / line-height | Weight | Use |
|---|---|---|---|
| `display` | 40 / 48 | 700 | Landing hero only |
| `h1` | 30 / 38 | 650 | Page title |
| `h2` | 24 / 32 | 650 | Section |
| `h3` | 20 / 28 | 600 | Card title, question heading |
| `h4` | 16 / 24 | 600 | Sub-section, form section |
| `bodyLg` | 16 / 26 | 400 | **Question text, candidate answers** |
| `body` | 14 / 22 | 400 | Default UI text |
| `bodySm` | 13 / 20 | 400 | Table cells, secondary |
| `caption` | 12 / 16 | 500 | Labels, metadata, helper text |
| `overline` | 11 / 16 | 600, +0.06em, uppercase | Group labels, table headers |
| `code` | 13 / 20 mono | 400 | Inline code, identifiers |
| `numeric` | tabular-nums | — | Applied to all scores, durations, counts |

Reasoning: `bodyLg` at 16/26 for question and answer text is a comprehension
decision — 14 px is fine for chrome, wrong for reading a paragraph you're being
graded on. `tabular-nums` on every score prevents columns jittering.

### 2.3 Spacing

4 px base scale: `0, 1(4), 2(8), 3(12), 4(16), 5(20), 6(24), 8(32), 10(40), 12(48), 16(64), 20(80)`.
MUI `spacing: 4` so `sx={{ p: 4 }}` = 16 px. Only scale values are permitted.

Layout constants: page gutter 24 px (mobile 16), content max-width 1280 px, prose
max-width **72ch** (question/answer/report text), card padding 24 px, form field gap
20 px, section gap 32 px.

### 2.4 Radius, border, elevation

```
radius:  xs 4 · sm 6 · md 8 (default: buttons, inputs, chips)
         lg 12 (cards, dialogs) · xl 16 (marketing) · full 9999 (avatar, pill)
border:  1px solid neutral/200 (default) · neutral/300 (strong) · brand/500 (selected)
```

Elevation is **borders first, shadows sparingly** — flat surfaces with crisp borders
read as "tool", stacked shadows read as "template".

```
e0  none                                  page, most cards
e1  0 1px 2px rgba(15,23,42,.06)          hover card, sticky header
e2  0 4px 8px -2px rgba(15,23,42,.08)     dropdown, popover
e3  0 12px 24px -6px rgba(15,23,42,.12)   dialog, drawer
```

### 2.5 Breakpoints

`xs 0 · sm 640 · md 900 · lg 1200 · xl 1536` (MUI defaults with `sm` nudged to 640).

| Surface | Behaviour |
|---|---|
| Landing, auth, results, history | Fully responsive from 360 px |
| Dashboard | Sidebar collapses to a drawer below `md` |
| **Interview runner** | Single column always; optimised ≥ 768 px; below that, a "best on a larger screen" notice, not a block |
| Admin tables | Horizontally scrollable container below `lg`; never a squashed table |

### 2.6 Motion

Duration `fast 120ms · base 180ms · slow 240ms`; easing `cubic-bezier(.2,0,0,1)`.
Only opacity/transform/background transition. No entrance animations on page load,
no staggered lists, no skeleton shimmer sweeps (a static tinted block is calmer).
`prefers-reduced-motion: reduce` disables all non-essential transitions.

### 2.7 Focus and accessibility baseline

- Focus ring: `0 0 0 2px #fff, 0 0 0 4px brand/300` — visible on every interactive
  element, never removed.
- Target WCAG 2.1 AA: 4.5:1 body text, 3:1 large text and UI boundaries.
- Every icon-only button has `aria-label`. Every input has a real `<label>`.
- Dialogs trap focus and restore it on close; `Esc` closes non-destructive dialogs.
- Live regions: evaluation progress and toasts use `aria-live="polite"`; submission
  errors use `role="alert"`.
- The interview runner is fully keyboard-operable: `Ctrl/Cmd+Enter` submits.
- Score is never communicated by colour alone (number + band label always present).

## 3. Theme implementation

Single source of truth: `src/theme/`.

```
src/theme/
  tokens.ts        raw primitives (colour ramps, space, radius, shadow, duration)
  palette.ts       token -> MUI palette (incl. custom `score` channel)
  typography.ts    token -> MUI typography variants (+ custom variants)
  components.ts    MUI component defaults & variants (the bulk of the file)
  index.ts         createTheme(...) + module augmentation for custom variants
```

Key defaults set globally in `components.ts` (so no component re-specifies them):
`Button` → `disableElevation`, `size: medium`, radius md, no text-transform;
`TextField` → `size: small`, `variant: outlined`, `fullWidth`;
`Card` → `variant: outlined`, radius lg, `elevation 0`;
`Table` → dense padding, `overline` header, `neutral/50` header background;
`Dialog` → radius lg, `e3`;
`Chip` → radius sm, height 24, `caption` weight 500.

TypeScript module augmentation declares the custom variants (`bodyLg`, `numeric`,
`code`, `overline`) and the `score` palette channel so `sx` and `variant` stay typed.

## 4. Component inventory

### 4.1 Primitives (thin wrappers over MUI — reason for each wrapper)

| Component | Why it exists rather than using MUI directly |
|---|---|
| `Button` | Locks variants to `primary / secondary / ghost / danger`; adds `loading` (spinner + disabled + preserved width, prevents layout jump and double-submit) |
| `TextInput` | Unifies label / helper / error / required affordance; wires `react-hook-form` + Zod error message |
| `TextArea` | Autosize with min/max rows, character counter, `bodyLg` type — used for answers |
| `Select` | Same wiring as `TextInput`, consistent empty state |
| `Card` | `CardHeader`/`CardBody`/`CardFooter` slots, standard padding, optional `interactive` state |
| `Dialog` | Standard header/body/footer, focus trap, destructive variant with confirm-word option |
| `Badge` | Status semantics: `neutral / info / success / warning / danger` |
| `Chip` | Skill and tag display, removable variant |
| `Table` | Wraps DataTable primitives: sticky header, zebra off, row hover, dense mode |
| `Tabs` | Underline style, URL-synced via `searchParams` |
| `Progress` | Linear + circular; determinate only (indeterminate reserved for unknown waits) |
| `Alert` | Inline (not toast) for page-level messages; icon + title + body + optional action |
| `EmptyState` | Icon, title, one-line explanation, primary action — used everywhere a list can be empty |
| `Skeleton` | Static tinted blocks matching real content geometry |
| `Toast` | Transient confirmations only; never for errors that need action |

### 4.2 Patterns (composed, product-aware)

| Pattern | Contract |
|---|---|
| `PageHeader` | breadcrumb?, title, subtitle?, meta chips?, actions? — every non-runner page starts with it |
| `StatCard` | label, value (numeric variant), delta?, hint? — dashboard tiles |
| `DataTable` | columns, rows, sort, pagination, row click, loading skeleton, empty state, error state — one table implementation, no bespoke tables |
| `FilterBar` | search + filter chips, URL-synced, "clear all" |
| `FormSection` | title, description, fields grid — used by template/question authoring |
| `ScoreCard` | score 0–10, band label, score-scale colour, optional per-skill breakdown |
| `SkillScoreBar` | skill name, weight, score, bar; groups into a skill profile |
| `InterviewProgress` | "Question 4 of 8" + segmented bar (answered / current / pending / follow-up) + remaining time |
| `QuestionPanel` | question text (`bodyLg`, 72ch), skill chip, difficulty badge, optional context block |
| `AnswerEditor` | `TextArea` + char counter + autosave indicator ("Saved 2s ago") + submit/skip |
| `RubricResultList` | criterion rows: verdict icon, label, evidence quote, weight — the core report component |
| `EvidenceHighlight` | renders an answer with evidence spans highlighted and cross-linked to criteria |
| `ProvenanceFooter` | **admin only**: model, prompt version, rubric version, latency, tokens, cost |
| `StatusTimeline` | attempt state history, used in admin attempt detail |

### 4.3 States every list/data component must implement

`loading` (skeleton) · `empty` (EmptyState) · `error` (Alert + retry) · `partial`
(some data unavailable — used for `COMPLETED_PARTIAL` reports) · `loaded`.
This is enforced by the `DataTable` and `QueryBoundary` wrapper components; a feature
screen that hand-rolls these is a review reject.

## 5. Form conventions

- Labels above fields, 13 px `caption` weight 500, never placeholder-as-label.
- Required marked with a `*` and `aria-required`; optional fields marked "(optional)"
  when a form is mostly required.
- Validation: Zod schema is the single source; `react-hook-form` mode `onTouched`,
  re-validate `onChange` after first error. Never validate while first typing.
- Errors appear under the field, 13 px, `danger/700`, with an icon; the field border
  turns `danger/500`. A form-level `Alert` summarises server-side failures.
- Submit buttons: `loading` state, disabled while pending, never disabled purely
  because the form is untouched (that hides why nothing happens).
- Destructive actions require a confirmation dialog naming the object.

## 6. Table conventions

Dense (36 px rows) · `overline` headers on `neutral/50` · left-aligned text,
right-aligned numerics with `tabular-nums` · sortable columns show direction ·
max 7 visible columns before overflow menu · row click navigates, and the row also
exposes a keyboard-focusable link (never a click handler alone) · pagination is
cursor-based, showing "Showing 1–25" without a total count where a count is expensive.

## 7. Feedback state conventions

| Situation | Mechanism |
|---|---|
| Action succeeded, no navigation | Toast, 4 s |
| Action succeeded with navigation | Inline `Alert` on the destination |
| Recoverable error | Inline `Alert` with retry action |
| Field error | Under-field message |
| Long operation (evaluation, report) | Determinate progress + real counts, never a bare spinner |
| Degraded data (partial report) | Amber inline notice explaining precisely what is missing and why |
| Empty | `EmptyState` with an action, never "No data" alone |

## 8. Interview runner: specific UI rules

The runner is where the product is judged. Rules:

1. One question visible. No sidebar, no navigation, no notification badges.
2. Sticky top bar: `InterviewProgress` + remaining time + "Finish early".
   Time turns amber at 5 min, `danger` at 1 min — colour only, never a countdown modal.
3. Answer editor is focused on question load; `Ctrl/Cmd+Enter` submits.
4. Autosave indicator is always visible — anxiety about losing an answer is the
   number-one abandonment cause.
5. Follow-up questions are visually marked ("Follow-up") and indented, so the
   candidate understands why they got another question on the same topic.
6. Submit → optimistic transition to a brief "Answer recorded" state, then the next
   question. **We never show an evaluating spinner tied to the model call.**
7. No score, no feedback, no hints during the interview (per 01 §12 Q1).

## 9. Assumptions

- MUI v6+ with the CSS-variables theme (`cssVariables: true`) — required for a clean
  dark-mode/white-label path later.
- No design tool source of truth in Phase 0; this document + `theme/tokens.ts` is it.
- Emotion is acceptable as the styling engine (MUI default); no Tailwind, no second
  styling system.

## 10. Risks

| Risk | Mitigation |
|---|---|
| Design drift: features hand-roll styles | Zero raw hex/px in feature code, enforced by an ESLint rule (`no-restricted-syntax` on hex literals in `features/**`) |
| MUI defaults leaking an inconsistent look | All defaults centralised in `theme/components.ts`; features never pass `sx` for spacing that a variant should own |
| Over-abstraction: wrappers that add nothing | Each wrapper in §4.1 justifies itself in the table; a wrapper that only re-exports MUI is deleted |
| Accessibility regressions | `eslint-plugin-jsx-a11y` in CI, plus an axe check in the component test suite |
| Score colours misread as UI state | Score palette is a separate channel, never used for buttons, chips or alerts |
