import type { CriterionResult, InterviewResult, Verdict } from '../api/types'

interface Props {
  result: InterviewResult
}

/**
 * The final screen.
 *
 * Every number here came from the backend. Nothing is recomputed, averaged or
 * rounded into a different figure on the way to the screen — a score a browser
 * derived is a score a browser could disagree with, and the whole reproducibility
 * argument would collapse at the last step.
 */
export function ResultScreen({ result }: Props) {
  const graded = result.questions.filter((question) => question.graded)
  const ungraded = result.questions.filter(
    (question) => !question.graded && question.status !== 'PENDING',
  )

  return (
    <section className="card" aria-labelledby="result-heading">
      <h1 id="result-heading">Interview Result</h1>

      <div className="score-block">
        <p className="muted">Overall Score</p>
        {/* Null means nothing could be graded. Rendering 0% would accuse the
            candidate of failing at something that was our outage. */}
        <p className="score">{result.overallScore ? formatScore(result.overallScore) : '—'}</p>
        {!result.overallScore && (
          <p className="muted small">
            We could not grade this interview, so no score is available.
          </p>
        )}
      </div>

      {result.skills.length > 0 && (
        <>
          <h2>Skills</h2>
          <ul className="skills">
            {result.skills.map((skill) => (
              <li key={skill.skillId}>
                <span>{skill.name ?? skill.code ?? 'Skill'}</span>
                <strong>{skill.score ? formatScore(skill.score) : '—'}</strong>
              </li>
            ))}
          </ul>
        </>
      )}

      <h2>Criteria</h2>
      {graded.length === 0 && <p className="muted">No questions were graded.</p>}

      {graded.map((question) => (
        <article key={question.interviewQuestionId} className="question-result">
          <h3>
            {question.isFollowUp ? 'Follow-up' : `Question ${question.position}`}
            {question.score && <span className="muted"> · {formatScore(question.score)}</span>}
          </h3>
          {question.promptText && <p className="muted small">{question.promptText}</p>}

          <ul className="criteria">
            {question.criteria.map((criterion) => (
              <CriterionRow key={criterion.criterionId} criterion={criterion} />
            ))}
          </ul>
        </article>
      ))}

      {ungraded.length > 0 && (
        <p className="muted small">
          {ungraded.length} question{ungraded.length === 1 ? ' was' : 's were'} not graded and
          {ungraded.length === 1 ? ' was' : ' were'} excluded from the score.
        </p>
      )}
    </section>
  )
}

function CriterionRow({ criterion }: { criterion: CriterionResult }) {
  return (
    <li>
      <p className={`verdict verdict-${criterion.verdict.toLowerCase()}`}>
        <span aria-hidden="true">{VERDICT_ICON[criterion.verdict]}</span>{' '}
        {VERDICT_LABEL[criterion.verdict]}
        <span className="criterion-name"> · {criterion.label ?? criterion.code}</span>
      </p>
      {/* The quote is the point of the product: it is a verbatim span of the
          candidate's own answer, so a verdict can always be checked against
          what they actually wrote. */}
      {criterion.evidenceQuote && (
        <blockquote>
          <p>Evidence:</p>
          <q>{criterion.evidenceQuote}</q>
        </blockquote>
      )}
    </li>
  )
}

const VERDICT_LABEL: Record<Verdict, string> = {
  MET: 'Meets',
  PARTIAL: 'Partial',
  MISSING: 'Not addressed',
  CONTRADICTED: 'Incorrect',
}

const VERDICT_ICON: Record<Verdict, string> = {
  MET: '✓',
  PARTIAL: '◐',
  MISSING: '○',
  CONTRADICTED: '✗',
}

/**
 * The backend's scale is **0–10**, not 0–1.
 *
 * `ScoringPolicy.MAX_SCORE` is ten and the database constrains
 * `derived_score BETWEEN 0 AND 10`. Scores arrive as decimal strings so JSON
 * cannot round them, and are parsed only here, for display. Getting this wrong
 * renders 6.15 as "615%" — which is exactly what a first run produced.
 */
const MAX_SCORE = 10

function formatScore(score: string): string {
  const value = Number(score)
  if (Number.isNaN(value)) {
    return '—'
  }
  return `${Math.round((value / MAX_SCORE) * 100)}%`
}
