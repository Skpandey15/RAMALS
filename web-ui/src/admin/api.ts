import { authenticatedFetch } from '../auth/authClient';
import { beginInteraction, toApiError } from '../platform/apiClient';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export interface AdminOperationalSnapshot {
  readonly learnersTotal: number;
  readonly learnersActive: number;
  readonly learnersSuspended: number;
  readonly learnersClosed: number;
  readonly learnersOnboarded: number;
  readonly curriculaDraft: number;
  readonly curriculaPublished: number;
  readonly curriculaRetired: number;
  readonly authorizationDenials24h: number;
  readonly adminActions24h: number;
}

export interface AdminLearner {
  readonly learnerId: string;
  readonly subject: string;
  readonly status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
  readonly firstName: string | null;
  readonly lastName: string | null;
  readonly email: string | null;
  readonly mobile: string | null;
  readonly countryCode: string | null;
  readonly city: string | null;
  readonly emailVerified: boolean;
  readonly mobileVerified: boolean;
  readonly onboardingState: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AdminCurriculum {
  readonly curriculumVersionId: string;
  readonly domainCode: string;
  readonly versionCode: string;
  readonly status: 'DRAFT' | 'PUBLISHED' | 'RETIRED';
  readonly publishedAt: string | null;
}

export interface AdminAuditEvent {
  readonly id: string;
  readonly actorSubject: string;
  readonly action: string;
  readonly targetType: string;
  readonly targetId: string | null;
  readonly outcome: string;
  readonly detail: string | null;
  readonly interactionId: string | null;
  readonly traceId: string | null;
  readonly createdAt: string;
}

export interface SecurityAuditEvent {
  readonly id: string;
  readonly eventType: string;
  readonly outcome: string;
  readonly subject: string | null;
  readonly httpMethod: string | null;
  readonly route: string | null;
  readonly statusCode: number | null;
  readonly reasonCode: string | null;
  readonly detail: string | null;
  readonly interactionId: string | null;
  readonly traceId: string | null;
  readonly createdAt: string;
}

export interface AdminIdentityUser {
  readonly id: string;
  readonly username: string | null;
  readonly email: string | null;
  readonly enabled: boolean;
  readonly realmRoles: readonly string[];
}

async function request<T>(path: string, init: RequestInit = { method: 'GET' }): Promise<T> {
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

export const getOperationalSnapshot = () =>
  request<AdminOperationalSnapshot>('/api/v1/admin/operations/snapshot');

export const listLearners = () => request<AdminLearner[]>('/api/v1/admin/learners');

export const changeLearnerStatus = (learnerId: string, status: AdminLearner['status']) =>
  request<AdminLearner>(`/api/v1/admin/learners/${encodeURIComponent(learnerId)}/status`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  });

export const listCurricula = () => request<AdminCurriculum[]>('/api/v1/admin/curricula');

export const publishCurriculum = (id: string) =>
  request<AdminCurriculum>(`/api/v1/admin/curricula/${encodeURIComponent(id)}/publish`, {
    method: 'POST',
  });

export const retireCurriculum = (id: string) =>
  request<AdminCurriculum>(`/api/v1/admin/curricula/${encodeURIComponent(id)}/retire`, {
    method: 'POST',
  });

export const listAdminAudit = () =>
  request<AdminAuditEvent[]>('/api/v1/admin/audit/admin-activity?limit=50');

export const listSecurityAudit = () =>
  request<SecurityAuditEvent[]>('/api/v1/admin/audit/security?limit=50');

export const listIdentities = () => request<AdminIdentityUser[]>('/api/v1/admin/identities');

export const setIdentityEnabled = (userId: string, enabled: boolean) =>
  request<AdminIdentityUser>(`/api/v1/admin/identities/${encodeURIComponent(userId)}/enabled`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  });

export const addIdentityRole = (userId: string, role: 'INSTRUCTOR' | 'CONTENT_AUTHOR') =>
  request<AdminIdentityUser>(
    `/api/v1/admin/identities/${encodeURIComponent(userId)}/roles/${encodeURIComponent(role)}`,
    { method: 'POST' },
  );

export const removeIdentityRole = (userId: string, role: 'INSTRUCTOR' | 'CONTENT_AUTHOR') =>
  request<AdminIdentityUser>(
    `/api/v1/admin/identities/${encodeURIComponent(userId)}/roles/${encodeURIComponent(role)}`,
    { method: 'DELETE' },
  );
