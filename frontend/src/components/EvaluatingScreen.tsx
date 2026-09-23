/**
 * Shown while grading runs.
 *
 * Says what is happening and that the answer is safe, and deliberately says
 * nothing about which provider is grading, how long it usually takes, or any
 * partial verdict. The first is an internal detail, and the last would leak the
 * feedback the interview is designed to withhold until the end.
 */
export function EvaluatingScreen() {
  return (
    <section className="card centred" aria-live="polite">
      <div className="spinner" aria-hidden="true" />
      <h2>Analyzing your answer…</h2>
      <p className="muted">Please wait while your response is evaluated.</p>
      <p className="muted small">Your answer has been saved. You can safely leave this page.</p>
    </section>
  )
}
