import { ApiError } from '../api/client'
import type { InterviewResult, InterviewState } from '../api/types'

/**
 * The screen's state, as one value that cannot contradict itself.
 *
 * Separate `loading`, `submitting`, `evaluating` and `completed` booleans admit
 * sixteen combinations, most of them nonsense — "submitting and completed" has
 * no meaning, but nothing stops it being rendered. A discriminated union admits
 * exactly the states that exist, and the data each one needs travels with it:
 * there is no way to be in `QUESTION` without a question, or in `RESULT`
 * without a result.
 *
 * This is presentation state only. The backend remains authoritative for the
 * interview's actual lifecycle; every transition below is a reaction to what a
 * response said, never a local decision about what must be true.
 */
export type UiState =
  /** Nothing started yet: the landing screen. */
  | { kind: 'START'; templateTitle: string | null }
  /** A request is in flight and there is nothing useful to show behind it. */
  | { kind: 'LOADING'; message: string }
  /** A question is on screen, awaiting an answer. */
  | { kind: 'QUESTION'; interview: InterviewState }
  /** The answer is being sent. The form is locked from here. */
  | { kind: 'SUBMITTING'; interview: InterviewState; answer: string }
  /** The answer is stored and grading is running. Polling happens here. */
  | { kind: 'EVALUATING'; interview: InterviewState }
  /** The interview is over; the result is being fetched. */
  | { kind: 'COMPLETED'; interview: InterviewState }
  /** The final screen. */
  | { kind: 'RESULT'; interview: InterviewState; result: InterviewResult }
  /**
   * Something failed.
   *
   * `recoverable` distinguishes "you may try that again" from "this interview
   * cannot continue", which is the difference between offering a retry button
   * and offering a way out.
   */
  | { kind: 'ERROR'; error: ApiError; recoverable: boolean; previous: UiState | null }

export type UiEvent =
  | { type: 'TEMPLATE_LOADED'; title: string }
  | { type: 'STARTING' }
  | { type: 'QUESTION_READY'; interview: InterviewState }
  | { type: 'SUBMIT'; answer: string }
  | { type: 'SUBMITTED'; interview: InterviewState }
  | { type: 'GRADING_SETTLED'; interview: InterviewState }
  | { type: 'COMPLETING'; interview: InterviewState }
  | { type: 'RESULT_READY'; interview: InterviewState; result: InterviewResult }
  | { type: 'FAILED'; error: ApiError; recoverable: boolean }
  | { type: 'RETRY' }

export const initialState: UiState = { kind: 'START', templateTitle: null }

/**
 * Whether the interview has stopped accepting answers.
 *
 * Read from the backend's status rather than inferred from "no current
 * question": a plan can be momentarily exhausted while grading settles and more
 * follow-ups are still possible, and treating that as the end would cut the
 * interview short.
 */
export function isFinished(interview: InterviewState): boolean {
  return interview.status !== 'IN_PROGRESS'
}

export function reduce(state: UiState, event: UiEvent): UiState {
  switch (event.type) {
    case 'TEMPLATE_LOADED':
      return state.kind === 'START' ? { kind: 'START', templateTitle: event.title } : state

    case 'STARTING':
      return { kind: 'LOADING', message: 'Preparing your interview…' }

    case 'QUESTION_READY':
      return { kind: 'QUESTION', interview: event.interview }

    case 'SUBMIT':
      // Only from QUESTION. A second SUBMIT while already submitting is exactly
      // the double-click this guard exists to absorb.
      return state.kind === 'QUESTION'
        ? { kind: 'SUBMITTING', interview: state.interview, answer: event.answer }
        : state

    case 'SUBMITTED':
      return { kind: 'EVALUATING', interview: event.interview }

    case 'GRADING_SETTLED':
      return { kind: 'EVALUATING', interview: event.interview }

    case 'COMPLETING':
      return { kind: 'COMPLETED', interview: event.interview }

    case 'RESULT_READY':
      return { kind: 'RESULT', interview: event.interview, result: event.result }

    case 'FAILED':
      return {
        kind: 'ERROR',
        error: event.error,
        recoverable: event.recoverable,
        // Kept so a recoverable failure can drop the user back where they were
        // rather than restarting the interview.
        previous: state.kind === 'ERROR' ? state.previous : state,
      }

    case 'RETRY':
      if (state.kind !== 'ERROR' || !state.recoverable) {
        return state
      }
      return state.previous ?? initialState

    default:
      return state
  }
}
