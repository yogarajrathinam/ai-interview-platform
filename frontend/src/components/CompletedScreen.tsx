/**
 * The gap between "the candidate is finished" and "the result exists".
 *
 * A real state, not a spinner for its own sake: the attempt sits in COMPLETING
 * while the last answers finish grading, and telling the candidate their report
 * is being prepared is what distinguishes that from a broken result screen.
 */
export function CompletedScreen() {
  return (
    <section className="card centred" aria-live="polite">
      <h2>Interview Complete</h2>
      <p className="muted">Thank you for completing the interview.</p>
      <div className="spinner" aria-hidden="true" />
      <p className="muted small">Preparing your result…</p>
    </section>
  )
}
