import { request } from './client'
import type {
  InterviewResult,
  InterviewState,
  SubmitAnswerResponse,
  TemplateSummary,
} from './types'

/**
 * Every call the candidate app can make, named after what it does rather than
 * after its URL. Components import these, never a path — so a route change is
 * one edit here, not a search across the app.
 */

export function getTemplate(templateKey: string, signal?: AbortSignal): Promise<TemplateSummary> {
  return request(`/interview-templates/${encodeURIComponent(templateKey)}`, { signal })
}

/**
 * Starts an attempt, or resumes the candidate's live one.
 *
 * Safe to call twice: the backend returns the existing attempt rather than
 * creating a second, so a double-clicked start button is harmless.
 */
export function startInterview(
  candidateUserId: string,
  templateKey: string,
  signal?: AbortSignal,
): Promise<InterviewState> {
  return request('/interviews', {
    method: 'POST',
    body: { candidateUserId, templateKey },
    signal,
  })
}

/** The authoritative state. This is what polling reads; it changes nothing. */
export function getState(interviewId: string, signal?: AbortSignal): Promise<InterviewState> {
  return request(`/interviews/${interviewId}`, { signal })
}

/**
 * Asks for the next question to be served.
 *
 * Idempotent — calling it twice returns the same turn rather than skipping one —
 * which is what makes it safe to call after a refresh.
 */
export function advance(interviewId: string, signal?: AbortSignal): Promise<InterviewState> {
  return request(`/interviews/${interviewId}/advance`, { method: 'POST', signal })
}

/**
 * Submits an answer for a named turn.
 *
 * The turn is named explicitly rather than implied by "the current question", so
 * a retry from a flaky connection is recognisably the same submission. The
 * response's `duplicate` flag says whether the backend kept an existing answer.
 */
export function submitAnswer(
  interviewId: string,
  interviewQuestionId: string,
  contentText: string,
  timeSpentSec?: number,
  signal?: AbortSignal,
): Promise<SubmitAnswerResponse> {
  return request(`/interviews/${interviewId}/answers`, {
    method: 'POST',
    body: { interviewQuestionId, contentText, timeSpentSec },
    signal,
  })
}

/** Ends the attempt at the candidate's request. Repeat calls are harmless. */
export function completeInterview(
  interviewId: string,
  signal?: AbortSignal,
): Promise<InterviewState> {
  return request(`/interviews/${interviewId}/completion`, { method: 'POST', signal })
}

/** Fails with `RESULT_NOT_AVAILABLE` while the interview is still in progress. */
export function getResult(interviewId: string, signal?: AbortSignal): Promise<InterviewResult> {
  return request(`/interviews/${interviewId}/result`, { signal })
}
