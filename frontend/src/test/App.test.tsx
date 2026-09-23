import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../App'
import {
  INTERVIEW_ID,
  finishedState,
  followUpState,
  interviewResult,
  interviewState,
} from './fixtures'

/**
 * The candidate flow, driven through the real components.
 *
 * `fetch` is stubbed rather than the API module, so the client layer — status
 * handling, problem-document parsing, the error contract — is exercised too. A
 * test that mocked `api/interviews` would pass even if every request were
 * malformed.
 */

const CANDIDATE = '00000000-0000-4000-8000-00000000d0c1'
const TEMPLATE = 'backend-engineer'

interface Route {
  body: unknown
  status?: number
}

let routes: Map<string, Route[]>

/** Matches on "METHOD /path", the level at which these tests reason. */
function route(key: string, body: unknown, status = 200) {
  const existing = routes.get(key) ?? []
  existing.push({ body, status })
  routes.set(key, existing)
}

function renderApp() {
  return render(<App candidateUserId={CANDIDATE} templateKey={TEMPLATE} />)
}

beforeEach(() => {
  routes = new Map()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string | URL, init?: RequestInit) => {
      const path = String(url).replace('/api/v1', '')
      const key = `${init?.method ?? 'GET'} ${path}`
      const queued = routes.get(key)

      if (!queued || queued.length === 0) {
        return jsonResponse({ code: 'NOT_FOUND', detail: `No stub for ${key}` }, 404)
      }
      // The last stub repeats, so a polling loop does not need a stub per tick.
      const next = queued.length > 1 ? queued.shift()! : queued[0]
      return jsonResponse(next.body, next.status ?? 200)
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'trace-abc' },
  })
}

function stubTemplate() {
  route(`GET /interview-templates/${TEMPLATE}`, {
    templateKey: TEMPLATE,
    version: 1,
    title: 'Backend Engineer Interview',
    level: 'MID',
    coreQuestionCount: 2,
    targetDurationMin: 25,
    hardDurationMin: 60,
    instructions: null,
  })
}

describe('Start', () => {
  it('shows the interview title and a start button', async () => {
    stubTemplate()
    renderApp()

    expect(
      await screen.findByRole('heading', { name: /backend engineer interview/i }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start interview/i })).toBeInTheDocument()
  })

  it('renders without a title when the template cannot be loaded', async () => {
    renderApp()

    // Not fatal: the candidate can still begin.
    expect(
      await screen.findByRole('button', { name: /start interview/i }),
    ).toBeInTheDocument()
  })

  it('starts an interview and shows the first question', async () => {
    stubTemplate()
    route('POST /interviews', interviewState())
    route(`POST /interviews/${INTERVIEW_ID}/advance`, interviewState())
    const user = userEvent.setup()
    renderApp()

    await user.click(await screen.findByRole('button', { name: /start interview/i }))

    expect(
      await screen.findByRole('heading', { name: /optimistic and pessimistic locking/i }),
    ).toBeInTheDocument()
    expect(screen.getByText(/question 1 of 2/i)).toBeInTheDocument()
  })
})

describe('Answering', () => {
  /**
   * Advance stubs are supplied in the order they will be consumed, because the
   * flow calls advance twice: once to serve the first question, and again after
   * grading settles. Registering them afterwards would let the first stub — the
   * last one still queued — repeat and silently re-serve question one.
   */
  async function startAndReachQuestion(advanceResponses: unknown[] = [interviewState()]) {
    stubTemplate()
    route('POST /interviews', interviewState())
    advanceResponses.forEach((response) =>
      route(`POST /interviews/${INTERVIEW_ID}/advance`, response),
    )
    const user = userEvent.setup()
    renderApp()
    await user.click(await screen.findByRole('button', { name: /start interview/i }))
    await screen.findByRole('heading', { name: /optimistic and pessimistic locking/i })
    return user
  }

  it('disables submit until something has been typed', async () => {
    const user = await startAndReachQuestion()

    const submit = screen.getByRole('button', { name: /submit answer/i })
    expect(submit).toBeDisabled()

    await user.type(screen.getByLabelText(/your answer/i), 'A real answer.')
    expect(submit).toBeEnabled()
  })

  it('shows the evaluating state after submitting, and locks the form', async () => {
    const user = await startAndReachQuestion()
    const answered = interviewState({
      progress: {
        ...interviewState().progress,
        answeredTotal: 1,
        evaluatedTotal: 0,
        gradingSettled: false,
      },
    })
    route(`POST /interviews/${INTERVIEW_ID}/answers`, {
      interviewQuestionId: answered.currentQuestion!.interviewQuestionId,
      duplicate: false,
      state: answered,
    })
    // Still grading on the first poll, settled on the next.
    route(`GET /interviews/${INTERVIEW_ID}`, answered)

    await user.type(screen.getByLabelText(/your answer/i), 'Pessimistic takes a lock up front.')
    await user.click(screen.getByRole('button', { name: /submit answer/i }))

    expect(await screen.findByText(/analyzing your answer/i)).toBeInTheDocument()
    expect(screen.getByText(/your answer has been saved/i)).toBeInTheDocument()
  })

  it('submits only once even when the button is clicked repeatedly', async () => {
    const user = await startAndReachQuestion()
    const answered = interviewState({
      progress: { ...interviewState().progress, answeredTotal: 1, gradingSettled: false },
    })
    route(`POST /interviews/${INTERVIEW_ID}/answers`, {
      interviewQuestionId: answered.currentQuestion!.interviewQuestionId,
      duplicate: false,
      state: answered,
    })
    route(`GET /interviews/${INTERVIEW_ID}`, answered)

    await user.type(screen.getByLabelText(/your answer/i), 'An answer.')
    const submit = screen.getByRole('button', { name: /submit answer/i })
    await user.click(submit)
    await user.click(submit).catch(() => undefined)

    await screen.findByText(/analyzing your answer/i)

    const answerCalls = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.filter(
      ([url]) => String(url).endsWith('/answers'),
    )
    expect(answerCalls)
      .toHaveLength(1)
  })

  it('moves on to a follow-up and labels it as one', async () => {
    // Two advances: the first serves question one, the second serves the probe.
    const user = await startAndReachQuestion([interviewState(), followUpState()])
    const answered = interviewState({
      progress: { ...interviewState().progress, answeredTotal: 1, gradingSettled: true },
    })
    route(`POST /interviews/${INTERVIEW_ID}/answers`, {
      interviewQuestionId: answered.currentQuestion!.interviewQuestionId,
      duplicate: false,
      state: answered,
    })
    route(`GET /interviews/${INTERVIEW_ID}`, answered)

    await user.type(screen.getByLabelText(/your answer/i), 'An answer.')
    await user.click(screen.getByRole('button', { name: /submit answer/i }))

    expect(await screen.findByText(/follow-up question/i)).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: /when would you choose pessimistic locking/i }),
    ).toBeInTheDocument()
  })
})

describe('Completion', () => {
  it('shows the backend result once the interview finishes', async () => {
    stubTemplate()
    route('POST /interviews', finishedState())
    route(`GET /interviews/${INTERVIEW_ID}/result`, interviewResult())
    const user = userEvent.setup()
    renderApp()

    await user.click(await screen.findByRole('button', { name: /start interview/i }))

    expect(await screen.findByRole('heading', { name: /interview result/i })).toBeInTheDocument()
    // 0.78 from the backend, rendered as a percentage and never recomputed.
    // It appears as the overall score, the skill score and the question score,
    // which is why this asserts presence rather than uniqueness.
    expect(screen.getAllByText('78%').length).toBeGreaterThan(0)
    expect(screen.getAllByText(/pessimistic locking/i).length).toBeGreaterThan(0)
    expect(screen.getByText(/a lock is taken up front/i)).toBeInTheDocument()
    expect(screen.getByText(/meets/i)).toBeInTheDocument()
    expect(screen.getByText(/partial/i)).toBeInTheDocument()
  })

  it('shows a dash rather than zero when nothing could be graded', async () => {
    stubTemplate()
    route('POST /interviews', finishedState())
    route(
      `GET /interviews/${INTERVIEW_ID}/result`,
      interviewResult({ overallScore: null, questions: [], skills: [] }),
    )
    const user = userEvent.setup()
    renderApp()

    await user.click(await screen.findByRole('button', { name: /start interview/i }))

    await screen.findByRole('heading', { name: /interview result/i })
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.getByText(/could not grade this interview/i)).toBeInTheDocument()
  })
})

describe('Errors', () => {
  it('maps a backend error code to a specific message, not a generic one', async () => {
    stubTemplate()
    route(
      'POST /interviews',
      { code: 'LIVE_ATTEMPT_EXISTS', detail: 'already live', traceId: 'trace-abc' },
      409,
    )
    const user = userEvent.setup()
    renderApp()

    await user.click(await screen.findByRole('button', { name: /start interview/i }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/already have an interview in progress/i)
    expect(alert).not.toHaveTextContent(/something went wrong/i)
  })

  it('shows the trace id so a user can quote it', async () => {
    stubTemplate()
    route('POST /interviews', { code: 'INTERNAL_ERROR', traceId: 'trace-abc' }, 500)
    const user = userEvent.setup()
    renderApp()

    await user.click(await screen.findByRole('button', { name: /start interview/i }))

    expect(await screen.findByText('trace-abc')).toBeInTheDocument()
  })

  it('offers a retry for a recoverable failure only', async () => {
    stubTemplate()
    route('POST /interviews', { code: 'INTERNAL_ERROR', traceId: 'trace-abc' }, 500)
    const user = userEvent.setup()
    renderApp()

    await user.click(await screen.findByRole('button', { name: /start interview/i }))

    expect(await screen.findByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  it('does not offer a retry for a permanent rejection', async () => {
    stubTemplate()
    route('POST /interviews', { code: 'NOT_FOUND', traceId: 'trace-abc' }, 404)
    const user = userEvent.setup()
    renderApp()

    await user.click(await screen.findByRole('button', { name: /start interview/i }))

    await screen.findByRole('alert')
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()
  })
})

describe('Polling cleanup', () => {
  it('stops polling when the component unmounts', async () => {
    stubTemplate()
    route('POST /interviews', interviewState())
    route(`POST /interviews/${INTERVIEW_ID}/advance`, interviewState())
    const answered = interviewState({
      progress: { ...interviewState().progress, answeredTotal: 1, gradingSettled: false },
    })
    route(`POST /interviews/${INTERVIEW_ID}/answers`, {
      interviewQuestionId: answered.currentQuestion!.interviewQuestionId,
      duplicate: false,
      state: answered,
    })
    // Never settles, so the loop would run forever if unmount did not stop it.
    route(`GET /interviews/${INTERVIEW_ID}`, answered)

    const user = userEvent.setup()
    const { unmount } = renderApp()
    await user.click(await screen.findByRole('button', { name: /start interview/i }))
    await screen.findByLabelText(/your answer/i)
    await user.type(screen.getByLabelText(/your answer/i), 'An answer.')
    await user.click(screen.getByRole('button', { name: /submit answer/i }))
    await screen.findByText(/analyzing your answer/i)

    unmount()
    const afterUnmount = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length
    await new Promise((resolve) => setTimeout(resolve, 2600))

    await waitFor(() => {
      const now = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length
      // At most one request may already have been in flight at unmount.
      expect(now - afterUnmount).toBeLessThanOrEqual(1)
    })
  })
})
