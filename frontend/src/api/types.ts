/**
 * The backend's wire contract, mirrored.
 *
 * Hand-written rather than generated, because it is small and because writing it
 * out forces a decision about what the UI is allowed to assume. Two things the
 * types deliberately encode:
 *
 * - `currentQuestion` is nullable. There is no question to show once the plan is
 *   exhausted, and a UI that assumed otherwise would crash exactly at the end of
 *   a successful interview.
 * - Nothing in `InterviewState` carries a score. That is the backend's rule, and
 *   mirroring it here means a component literally cannot render a mid-interview
 *   mark, however tempting a "live feedback" feature later looks.
 */

export type InterviewStatus = 'IN_PROGRESS' | 'COMPLETING' | 'COMPLETED' | 'ABANDONED'

export type TurnStatus =
  | 'PENDING'
  | 'ASKED'
  | 'ANSWERED'
  | 'SKIPPED'
  | 'EVALUATED'
  | 'EVAL_FAILED'

export type TurnKind = 'CORE' | 'FOLLOW_UP'

export type Verdict = 'MET' | 'PARTIAL' | 'MISSING' | 'CONTRADICTED'

export type CompletionReason =
  | 'CANDIDATE_FINISHED'
  | 'ALL_ANSWERED'
  | 'TIME_EXPIRED'
  | 'AUTO_COMPLETED'
  | 'ABANDONED_BY_CANDIDATE'
  | 'ABANDONED_EXPIRED'
  | 'ABANDONED_BY_ADMIN'

export interface Progress {
  coreTotal: number
  coreAnswered: number
  followUpsAsked: number
  followUpsRemaining: number
  answeredTotal: number
  evaluatedTotal: number
  /** Every answer given so far has been graded. This is what polling waits on. */
  gradingSettled: boolean
}

export interface CurrentQuestion {
  interviewQuestionId: string
  position: number
  kind: TurnKind
  isFollowUp: boolean
  parentTurnId: string | null
  skillId: string
  promptText: string
  contextText: string | null
  expectedDurationSec: number
  /** Null until the question has actually been served. */
  askedAt: string | null
}

export interface TurnSummary {
  interviewQuestionId: string
  position: number
  kind: TurnKind
  status: TurnStatus
  skillId: string
  parentTurnId: string | null
}

export interface InterviewState {
  interviewId: string
  status: InterviewStatus
  completionReason: CompletionReason | null
  startedAt: string
  hardDeadlineAt: string
  serverTime: string
  progress: Progress
  currentQuestion: CurrentQuestion | null
  timeline: TurnSummary[]
}

export interface SubmitAnswerResponse {
  interviewQuestionId: string
  /** The answer was already stored. Success, not an error. */
  duplicate: boolean
  state: InterviewState
}

export interface TemplateSummary {
  templateKey: string
  version: number
  title: string
  level: string | null
  coreQuestionCount: number
  targetDurationMin: number
  hardDurationMin: number
  instructions: string | null
}

export interface CriterionResult {
  criterionId: string
  code: string | null
  label: string | null
  verdict: Verdict
  credit: string | null
  weightBp: number
  /** The candidate's own words. Null when none survived validation. */
  evidenceQuote: string | null
}

export interface QuestionResult {
  interviewQuestionId: string
  position: number
  kind: TurnKind
  isFollowUp: boolean
  status: TurnStatus
  skillId: string
  promptText: string | null
  score: string | null
  /** False when the turn was excluded from the average — our failure, not theirs. */
  graded: boolean
  criteria: CriterionResult[]
}

export interface SkillResult {
  skillId: string
  code: string | null
  name: string | null
  score: string | null
  weightBp: number
  coverageBp: number
  questionCount: number
}

export interface InterviewResult {
  interviewId: string
  status: InterviewStatus
  completionReason: CompletionReason | null
  /** Null when nothing could be graded. Must render as "no result", never 0. */
  overallScore: string | null
  coverageBp: number
  skills: SkillResult[]
  questions: QuestionResult[]
}
