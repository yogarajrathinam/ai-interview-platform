/**
 * The single place an HTTP call is made.
 *
 * Components never call `fetch`. Centralising it is what makes the error
 * contract usable: every failure arrives as one `ApiError` carrying the
 * backend's stable `code` and its `traceId`, so a screen can map a known code to
 * a sentence a candidate can act on, and a support request can quote an id that
 * finds the exact request in the logs.
 */

/** RFC 9457 problem document, as the backend's exception handler emits it. */
interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  code?: string
  traceId?: string
  errors?: { field: string; code: string; message: string }[]
}

/**
 * A failed request.
 *
 * `code` is the thing to branch on — never the HTTP status alone, and never the
 * message, which is written for a human and may be reworded at any time.
 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly traceId: string | null
  readonly detail: string | null

  constructor(code: string, status: number, detail: string | null, traceId: string | null) {
    super(detail ?? code)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.detail = detail
    this.traceId = traceId
  }

  /**
   * Whether retrying the identical request could plausibly work.
   *
   * Used to decide whether to offer a retry button — never to retry
   * automatically. A submitted answer may have been stored before the response
   * was lost, and silently resending is how duplicates and double-grading
   * happen. The user decides.
   */
  get isRetryable(): boolean {
    return this.status === 0 || this.status >= 500 || this.code === 'RATE_LIMITED'
  }
}

/** A transport failure, before any HTTP status existed. */
const NETWORK_ERROR = 'NETWORK_ERROR'

export interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  signal?: AbortSignal
}

const BASE_URL = '/api/v1'

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal } = options

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      signal,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (cause) {
    // An abort is the caller's own doing — usually an unmounted component — and
    // must not be reported to the user as a failure.
    if (cause instanceof DOMException && cause.name === 'AbortError') {
      throw cause
    }
    throw new ApiError(NETWORK_ERROR, 0, 'Could not reach the server.', null)
  }

  const traceId = response.headers.get('X-Trace-Id')

  if (!response.ok) {
    const problem = await safeJson<ProblemDetail>(response)
    throw new ApiError(
      problem?.code ?? 'INTERNAL_ERROR',
      response.status,
      problem?.detail ?? null,
      problem?.traceId ?? traceId,
    )
  }

  if (response.status === 204) {
    return undefined as T
  }

  const parsed = await safeJson<T>(response)
  if (parsed === null) {
    // 2xx with an unreadable body is a broken contract, not an empty result.
    throw new ApiError('INTERNAL_ERROR', response.status, 'Malformed response.', traceId)
  }
  return parsed
}

async function safeJson<T>(response: Response): Promise<T | null> {
  try {
    return (await response.json()) as T
  } catch {
    return null
  }
}

/**
 * Wording for the error codes this app can actually provoke.
 *
 * A generic "something went wrong" is a failure of this table, not of the
 * backend: every code here is one the API documents, and each gets a sentence
 * that tells the candidate what is true and what to do next.
 */
const MESSAGES: Record<string, string> = {
  NOT_FOUND: 'We could not find that interview. It may have been removed.',
  VALIDATION_FAILED: 'That answer could not be accepted. Please check it and try again.',
  MALFORMED_REQUEST: 'The request could not be read. Please try again.',
  INTERVIEW_NOT_IN_PROGRESS:
    'This interview has already finished, so no further answers can be recorded.',
  LIVE_ATTEMPT_EXISTS: 'You already have an interview in progress.',
  RESULT_NOT_AVAILABLE: 'The result is not ready yet. It appears once the interview is finished.',
  RATE_LIMITED: 'Too many requests. Please wait a moment and try again.',
  AI_UNAVAILABLE:
    'Grading is temporarily unavailable. Your answers are saved and will be graded shortly.',
  [NETWORK_ERROR]: 'Could not reach the server. Check your connection and try again.',
  INTERNAL_ERROR: 'Something went wrong on our side. Quote the reference below if you report it.',
}

export function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    return MESSAGES[error.code] ?? error.detail ?? MESSAGES.INTERNAL_ERROR
  }
  return MESSAGES.INTERNAL_ERROR
}
