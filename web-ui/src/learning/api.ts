import { authenticatedFetch } from '../auth/authClient';
import { beginInteraction, toApiError } from '../platform/apiClient';
import { createInteractionId } from '../platform/correlation';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export const DOMAIN = 'KAFKA';
export const VERSION = 'v1';

export interface DiagnosticItemOption {
  readonly id: string;
  readonly text: string;
}

export interface DiagnosticItem {
  readonly itemId: string;
  readonly itemCode: string;
  readonly skillCode: string;
  readonly itemType: string;
  readonly stem: string;
  readonly options: readonly DiagnosticItemOption[];
  readonly displayOrder: number;
}

export interface AttemptSummary {
  readonly attemptId: string;
  readonly status: string;
}

export interface AttemptDetail extends AttemptSummary {
  readonly items: readonly DiagnosticItem[];
}

export interface SkillScore {
  readonly skillCode: string;
  readonly itemsAnswered: number;
  readonly itemsCorrect: number;
  readonly observedScore: number;
  readonly normalizedScore: number;
}

export interface SubmissionResult {
  readonly attemptId: string;
  readonly status: string;
  readonly scoringVersion: string;
  readonly itemsAnswered: number;
  readonly skillScores: readonly SkillScore[];
}

export interface MasterySkill {
  readonly skillCode: string;
  readonly masteryScore: number;
  readonly evidenceConfidence: number;
  readonly masteryStatus: string;
  readonly aggregateVersion: number;
}

export interface MasteryMap {
  readonly domainCode: string;
  readonly versionCode: string;
  readonly skills: readonly MasterySkill[];
}

export interface Recommendation {
  readonly skillCode: string;
  readonly recommendedAction: string;
  readonly reasonCode: string;
  readonly masteryStatus: string;
  readonly decisionRecordId: string;
  readonly createdAt: string;
}

export interface Recommendations {
  readonly recommendations: readonly Recommendation[];
}

export type AssessmentFeedbackStatus =
  | 'EVALUATED'
  | 'REJECTED'
  | 'MANUAL_REVIEW'
  | 'UNAVAILABLE';

export interface EvaluationRubricResult {
  readonly dimensionId: string;
  readonly score: number;
  readonly maxScore: number;
  readonly feedback: string;
}

export interface ApprovedEvaluationFeedback {
  readonly answerVersion: string;
  readonly rubricVersion: string;
  readonly feedback: string;
  readonly rubricResults: readonly EvaluationRubricResult[];
  readonly nextLearningRationale: string;
  readonly evaluatedAt: string;
}

export interface AssessmentFeedback {
  readonly status: AssessmentFeedbackStatus;
  readonly approvedFeedback: ApprovedEvaluationFeedback | null;
}

export interface ItemResponseInput {
  readonly itemId: string;
  readonly selectedOptions: readonly string[];
}

async function apiRequest<T>(path: string, init: RequestInit): Promise<T> {
  const interaction = beginInteraction();
  const response = await authenticatedFetch(interaction, `${API_BASE_URL}${path}`, init);
  if (!response.ok) {
    throw await toApiError(response, interaction.interactionId);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function startDiagnostic(): Promise<AttemptSummary> {
  // Each attempt creation carries its own Idempotency-Key so a network retry cannot fork attempts.
  return apiRequest(`/api/v1/diagnostics/${DOMAIN}/attempts`, {
    method: 'POST',
    headers: { 'Idempotency-Key': createInteractionId() },
  });
}

export function getAttempt(attemptId: string): Promise<AttemptDetail> {
  return apiRequest(`/api/v1/diagnostics/${DOMAIN}/attempts/${attemptId}`, { method: 'GET' });
}

export function submitDiagnostic(
  attemptId: string,
  responses: readonly ItemResponseInput[],
): Promise<SubmissionResult> {
  return apiRequest(`/api/v1/diagnostics/${DOMAIN}/attempts/${attemptId}/submit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ responses }),
  });
}

export function getMasteryMap(): Promise<MasteryMap> {
  return apiRequest(`/api/v1/me/mastery/${DOMAIN}/versions/${VERSION}`, { method: 'GET' });
}

export function getRecommendations(): Promise<Recommendations> {
  return apiRequest('/api/v1/me/recommendations', { method: 'GET' });
}

export function getAssessmentFeedback(signal?: AbortSignal): Promise<AssessmentFeedback> {
  return apiRequest('/api/v1/me/assessment-evaluations/latest-feedback', {
    method: 'GET',
    signal,
  });
}
