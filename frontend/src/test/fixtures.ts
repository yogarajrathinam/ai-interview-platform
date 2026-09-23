import type { InterviewResult, InterviewState } from '../api/types'

/**
 * Backend-shaped fixtures.
 *
 * Written to match what the API actually returns rather than what a component
 * happens to need, so a test cannot pass against a shape the server never
 * sends. Every field is present for that reason, including the nullable ones.
 */

export const INTERVIEW_ID = '11111111-1111-4111-8111-111111111111'
export const TURN_ONE = '22222222-2222-4222-8222-222222222221'
export const TURN_TWO = '22222222-2222-4222-8222-222222222222'
const SKILL = '33333333-3333-4333-8333-333333333333'

export function interviewState(overrides: Partial<InterviewState> = {}): InterviewState {
  return {
    interviewId: INTERVIEW_ID,
    status: 'IN_PROGRESS',
    completionReason: null,
    startedAt: '2026-01-01T10:00:00Z',
    hardDeadlineAt: '2026-01-01T11:00:00Z',
    serverTime: '2026-01-01T10:00:05Z',
    progress: {
      coreTotal: 2,
      coreAnswered: 0,
      followUpsAsked: 0,
      followUpsRemaining: 2,
      answeredTotal: 0,
      evaluatedTotal: 0,
      gradingSettled: true,
    },
    currentQuestion: {
      interviewQuestionId: TURN_ONE,
      position: 1,
      kind: 'CORE',
      isFollowUp: false,
      parentTurnId: null,
      skillId: SKILL,
      promptText: 'Explain the difference between optimistic and pessimistic locking.',
      contextText: null,
      expectedDurationSec: 180,
      askedAt: '2026-01-01T10:00:05Z',
    },
    timeline: [
      {
        interviewQuestionId: TURN_ONE,
        position: 1,
        kind: 'CORE',
        status: 'ASKED',
        skillId: SKILL,
        parentTurnId: null,
      },
    ],
    ...overrides,
  }
}

export function followUpState(): InterviewState {
  const state = interviewState()
  return {
    ...state,
    progress: { ...state.progress, answeredTotal: 1, evaluatedTotal: 1, coreAnswered: 1 },
    currentQuestion: {
      interviewQuestionId: TURN_TWO,
      position: 2,
      kind: 'FOLLOW_UP',
      isFollowUp: true,
      parentTurnId: TURN_ONE,
      skillId: SKILL,
      promptText: 'When would you choose pessimistic locking?',
      contextText: null,
      expectedDurationSec: 120,
      askedAt: '2026-01-01T10:05:00Z',
    },
  }
}

export function finishedState(): InterviewState {
  const state = interviewState()
  return {
    ...state,
    status: 'COMPLETED',
    completionReason: 'ALL_ANSWERED',
    currentQuestion: null,
    progress: { ...state.progress, answeredTotal: 2, evaluatedTotal: 2, coreAnswered: 2 },
  }
}

export function interviewResult(overrides: Partial<InterviewResult> = {}): InterviewResult {
  return {
    interviewId: INTERVIEW_ID,
    status: 'COMPLETED',
    completionReason: 'ALL_ANSWERED',
    overallScore: '7.80',
    coverageBp: 10000,
    skills: [
      {
        skillId: SKILL,
        code: 'SQL',
        name: 'SQL',
        score: '7.80',
        weightBp: 10000,
        coverageBp: 10000,
        questionCount: 1,
      },
    ],
    questions: [
      {
        interviewQuestionId: TURN_ONE,
        position: 1,
        kind: 'CORE',
        isFollowUp: false,
        status: 'EVALUATED',
        skillId: SKILL,
        promptText: 'Explain the difference between optimistic and pessimistic locking.',
        score: '7.80',
        graded: true,
        criteria: [
          {
            criterionId: '44444444-4444-4444-8444-444444444441',
            code: 'PESSIMISTIC',
            label: 'Pessimistic locking',
            verdict: 'MET',
            credit: '1.00',
            weightBp: 5000,
            evidenceQuote: 'a lock is taken up front and blocks other writers',
          },
          {
            criterionId: '44444444-4444-4444-8444-444444444442',
            code: 'OPTIMISTIC',
            label: 'Optimistic locking',
            verdict: 'PARTIAL',
            credit: '0.50',
            weightBp: 5000,
            evidenceQuote: 'it checks at the end',
          },
        ],
      },
    ],
    ...overrides,
  }
}
