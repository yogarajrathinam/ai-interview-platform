import { messageFor, type ApiError } from '../api/client'

interface Props {
  error: ApiError
  recoverable: boolean
  onRetry: () => void
}

/**
 * The error screen.
 *
 * Two rules it exists to enforce. First, the message is mapped from the
 * backend's stable error code, so a candidate is told what actually happened
 * rather than "something went wrong" — a generic message here would mean the
 * error contract was built for nothing. Second, the trace id is shown, because
 * the one thing that makes a support request answerable is the id that finds
 * the exact request in the logs.
 *
 * Retry is offered only when repeating the request is safe. An answer may have
 * been stored before the response was lost, so resubmitting is never automatic.
 */
export function ErrorPanel({ error, recoverable, onRetry }: Props) {
  return (
    <section className="card error" role="alert">
      <h2>We hit a problem</h2>
      <p>{messageFor(error)}</p>

      {recoverable ? (
        <button type="button" className="primary" onClick={onRetry}>
          Try again
        </button>
      ) : (
        <p className="muted small">
          This interview cannot continue. Any answers you submitted have been saved.
        </p>
      )}

      {error.traceId && (
        <p className="muted small">
          Reference: <code>{error.traceId}</code>
        </p>
      )}
    </section>
  )
}
