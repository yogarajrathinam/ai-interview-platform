interface Props {
  title: string | null
  onStart: () => void
}

/**
 * The landing screen.
 *
 * Sets an expectation before the candidate commits: that follow-up questions
 * exist, and that answers are graded against a published rubric. Both change how
 * someone answers, and discovering them mid-interview feels like a trick.
 */
export function StartScreen({ title, onStart }: Props) {
  return (
    <section className="card" aria-labelledby="start-heading">
      <h1 id="start-heading">{title ?? 'Technical Interview'}</h1>
      <p>
        This interview contains technical questions and may ask follow-up questions based on
        your answers.
      </p>
      <p className="muted">
        Each answer is assessed against a published rubric. Your result is shown once the
        interview is finished, not during it.
      </p>
      <button type="button" className="primary" onClick={onStart}>
        Start Interview
      </button>
    </section>
  )
}
