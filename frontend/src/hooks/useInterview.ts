import { useCallback, useEffect, useReducer, useRef } from 'react'
import { ApiError } from '../api/client'
import * as api from '../api/interviews'
import type { InterviewState } from '../api/types'
import { initialState, isFinished, reduce, type UiState } from '../state/interviewMachine'

/** How often to ask whether grading has settled. */
const POLL_INTERVAL_MS = 1200

/**
 * Stops a stuck interview polling forever.
 *
 * Grading normally takes seconds. If it has not settled after this long the
 * honest thing is to say so and let the candidate continue or finish, rather
 * than leaving a spinner running indefinitely against a queue that may have
 * buried the job.
 */
const POLL_TIMEOUT_MS = 120_000

interface Options {
  candidateUserId: string
  templateKey: string
}

/**
 * Drives the whole candidate flow.
 *
 * All orchestration lives here so components stay presentational: they render a
 * `UiState` and call `start`, `submit` or `finish`. No component knows that
 * evaluation is asynchronous, or that the next question arrives from a
 * different call than the answer.
 *
 * Every async path is guarded against a component that has gone away. An
 * `AbortController` cancels in-flight requests on unmount, and a `mounted` flag
 * stops a late resolution from dispatching into a dead reducer — without both,
 * finishing an interview and navigating away logs React warnings and, worse,
 * leaves a polling loop running.
 */
export function useInterview({ candidateUserId, templateKey }: Options) {
  const [state, dispatch] = useReducer(reduce, initialState)

  const abortRef = useRef<AbortController | null>(null)
  const mountedRef = useRef(true)
  /** Guards against two polling loops existing at once. */
  const pollingRef = useRef(false)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      abortRef.current?.abort()
    }
  }, [])

  const signal = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = new AbortController()
    return abortRef.current.signal
  }, [])

  const fail = useCallback((error: unknown) => {
    if (error instanceof DOMException && error.name === 'AbortError') {
      return
    }
    if (!mountedRef.current) {
      return
    }
    const apiError =
      error instanceof ApiError
        ? error
        : new ApiError('INTERNAL_ERROR', 0, 'Unexpected error.', null)
    dispatch({ type: 'FAILED', error: apiError, recoverable: apiError.isRetryable })
  }, [])

  // The template title for the landing screen. Failure here is not fatal: the
  // candidate can still start, they just see a generic heading.
  useEffect(() => {
    const controller = new AbortController()
    api
      .getTemplate(templateKey, controller.signal)
      .then((template) => {
        if (mountedRef.current) {
          dispatch({ type: 'TEMPLATE_LOADED', title: template.title })
        }
      })
      .catch(() => undefined)
    return () => controller.abort()
  }, [templateKey])

  /**
   * Moves the interview forward after grading has settled.
   *
   * Advancing serves the next question, runs the follow-up phase when the core
   * plan is exhausted, and moves a finished attempt toward completion — so one
   * call covers all three, and the response says which happened.
   */
  const proceed = useCallback(
    async (interviewId: string) => {
      try {
        const next = await api.advance(interviewId, signal())
        if (!mountedRef.current) {
          return
        }
        if (next.currentQuestion && !isFinished(next)) {
          dispatch({ type: 'QUESTION_READY', interview: next })
        } else {
          dispatch({ type: 'COMPLETING', interview: next })
        }
      } catch (error) {
        fail(error)
      }
    },
    [fail, signal],
  )

  /**
   * Waits for grading, then proceeds.
   *
   * Polls the state endpoint rather than holding a request open, because the
   * backend grades asynchronously through a job worker and a blocking call
   * would put a multi-second model call on the candidate's connection.
   *
   * The loop stops on every exit: settled, finished, timed out, unmounted, or
   * failed. `pollingRef` prevents a second loop starting while one runs.
   */
  const pollUntilSettled = useCallback(
    async (interviewId: string) => {
      if (pollingRef.current) {
        return
      }
      pollingRef.current = true
      const startedAt = Date.now()

      try {
        for (;;) {
          if (!mountedRef.current) {
            return
          }

          const current = await api.getState(interviewId, signal())
          if (!mountedRef.current) {
            return
          }

          if (current.progress.gradingSettled || isFinished(current)) {
            dispatch({ type: 'GRADING_SETTLED', interview: current })
            await proceed(interviewId)
            return
          }

          if (Date.now() - startedAt > POLL_TIMEOUT_MS) {
            throw new ApiError(
              'AI_UNAVAILABLE',
              0,
              'Grading is taking longer than expected. Your answers are saved.',
              null,
            )
          }

          await sleep(POLL_INTERVAL_MS)
        }
      } catch (error) {
        fail(error)
      } finally {
        pollingRef.current = false
      }
    },
    [fail, proceed, signal],
  )

  const start = useCallback(async () => {
    dispatch({ type: 'STARTING' })
    try {
      const started = await api.startInterview(candidateUserId, templateKey, signal())
      if (!mountedRef.current) {
        return
      }
      // A resumed attempt may already be finished, in which case there is a
      // result to show rather than a question to ask.
      if (isFinished(started)) {
        dispatch({ type: 'COMPLETING', interview: started })
        return
      }
      await proceed(started.interviewId)
    } catch (error) {
      fail(error)
    }
  }, [candidateUserId, fail, proceed, signal, templateKey])

  const submit = useCallback(
    async (answer: string) => {
      if (state.kind !== 'QUESTION' || !state.interview.currentQuestion) {
        return
      }
      const { interviewId, currentQuestion } = state.interview
      dispatch({ type: 'SUBMIT', answer })

      try {
        const response = await api.submitAnswer(
          interviewId,
          currentQuestion.interviewQuestionId,
          answer,
          undefined,
          signal(),
        )
        if (!mountedRef.current) {
          return
        }
        // `duplicate` is deliberately not surfaced as an error: the answer is
        // stored either way, and telling the candidate their submission failed
        // would invite them to send it again.
        dispatch({ type: 'SUBMITTED', interview: response.state })
        await pollUntilSettled(interviewId)
      } catch (error) {
        fail(error)
      }
    },
    [fail, pollUntilSettled, signal, state],
  )

  const finish = useCallback(async () => {
    const interview = currentInterview(state)
    if (!interview) {
      return
    }
    try {
      const completed = await api.completeInterview(interview.interviewId, signal())
      if (mountedRef.current) {
        dispatch({ type: 'COMPLETING', interview: completed })
      }
    } catch (error) {
      fail(error)
    }
  }, [fail, signal, state])

  /**
   * Fetches the result once the interview is complete.
   *
   * `COMPLETING` means the candidate is done but grading is still settling, so
   * the result may not exist yet; that is a documented refusal rather than a
   * failure, and the poll below simply tries again.
   */
  useEffect(() => {
    if (state.kind !== 'COMPLETED') {
      return
    }
    const controller = new AbortController()
    let cancelled = false

    const load = async () => {
      for (let attempt = 0; attempt < 40 && !cancelled; attempt++) {
        try {
          const result = await api.getResult(state.interview.interviewId, controller.signal)
          if (!cancelled && mountedRef.current) {
            dispatch({ type: 'RESULT_READY', interview: state.interview, result })
          }
          return
        } catch (error) {
          if (error instanceof ApiError && error.code === 'RESULT_NOT_AVAILABLE') {
            // Still settling. Nudge the attempt along and wait.
            await api.completeInterview(state.interview.interviewId, controller.signal)
              .catch(() => undefined)
            await sleep(POLL_INTERVAL_MS)
            continue
          }
          if (!cancelled) {
            fail(error)
          }
          return
        }
      }
    }

    void load()
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [fail, state])

  const retry = useCallback(() => dispatch({ type: 'RETRY' }), [])

  return { state, start, submit, finish, retry }
}

function currentInterview(state: UiState): InterviewState | null {
  switch (state.kind) {
    case 'QUESTION':
    case 'SUBMITTING':
    case 'EVALUATING':
    case 'COMPLETED':
    case 'RESULT':
      return state.interview
    default:
      return null
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
