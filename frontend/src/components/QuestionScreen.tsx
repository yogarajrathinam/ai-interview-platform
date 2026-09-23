import { useEffect, useState } from 'react'
import type { InterviewState } from '../api/types'

interface Props {
  interview: InterviewState
  submitting: boolean
  onSubmit: (answer: string) => void
  onFinish: () => void
}

/**
 * The question and the answer box.
 *
 * The draft is local state keyed to the turn: it is cleared when the question
 * changes, so an answer cannot be carried into the next question by a stale
 * textarea. It is deliberately *not* cleared on submit, so that a failed
 * submission leaves the candidate's words on screen rather than discarding work
 * we could not store.
 */
export function QuestionScreen({ interview, submitting, onSubmit, onFinish }: Props) {
  const question = interview.currentQuestion
  const [draft, setDraft] = useState('')

  useEffect(() => {
    setDraft('')
  }, [question?.interviewQuestionId])

  if (!question) {
    return null
  }

  const { progress } = interview
  const total = progress.coreTotal + progress.followUpsAsked
  const empty = draft.trim().length === 0

  return (
    <section className="card" aria-labelledby="question-heading">
      <header className="question-header">
        <p className="muted">
          {question.isFollowUp
            ? 'Follow-up question'
            : `Question ${question.position} of ${total}`}
        </p>
        {question.isFollowUp && (
          <p className="badge">This probes your previous answer in more detail.</p>
        )}
      </header>

      <h2 id="question-heading">{question.promptText}</h2>
      {question.contextText && <p className="muted">{question.contextText}</p>}

      <label className="visually-hidden" htmlFor="answer">
        Your answer
      </label>
      <textarea
        id="answer"
        rows={10}
        value={draft}
        placeholder="Your answer…"
        disabled={submitting}
        onChange={(event) => setDraft(event.target.value)}
      />

      <div className="actions">
        <button
          type="button"
          className="primary"
          // Disabled while a submission is in flight: this is the first and
          // cheapest of the three defences against a double submit, the others
          // being the state machine's guard and the answer table's primary key.
          disabled={submitting || empty}
          onClick={() => onSubmit(draft)}
        >
          {submitting ? 'Submitting…' : 'Submit Answer'}
        </button>
        <button type="button" className="secondary" disabled={submitting} onClick={onFinish}>
          Finish interview
        </button>
      </div>

      <p className="muted small">
        {progress.answeredTotal} answered
        {progress.evaluatedTotal < progress.answeredTotal &&
          ` · ${progress.evaluatedTotal} graded so far`}
      </p>
    </section>
  )
}
