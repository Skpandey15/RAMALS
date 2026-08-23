import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RamalsApiError } from '../platform/apiClient';

// The component tests mock this whole module, so its real behaviour — correlation headers,
// idempotency keys, URL construction and error translation — is only exercised here.
vi.mock('../auth/authClient', () => ({
  authenticatedFetch: vi.fn(),
}));

import { authenticatedFetch } from '../auth/authClient';
import {
  getAttempt,
  getAssessmentFeedback,
  getMasteryMap,
  getRecommendations,
  startDiagnostic,
  submitDiagnostic,
} from './api';

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** The (interaction, url, init) triple the API client passed to the authenticated fetch. */
function lastCall() {
  const calls = vi.mocked(authenticatedFetch).mock.calls;
  const [interaction, url, init] = calls[calls.length - 1];
  return { interaction, url: String(url), init: init ?? {} };
}

describe('learning API client', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('creates an attempt with a fresh Idempotency-Key so a retry cannot fork attempts', async () => {
    // A Response body can only be read once, so each call needs a fresh instance.
    vi.mocked(authenticatedFetch).mockImplementation(async () =>
      jsonResponse({ attemptId: 'attempt-1', status: 'IN_PROGRESS' }),
    );

    const first = await startDiagnostic();
    const firstKey = new Headers(lastCall().init.headers).get('Idempotency-Key');
    await startDiagnostic();
    const secondKey = new Headers(lastCall().init.headers).get('Idempotency-Key');

    expect(first.attemptId).toBe('attempt-1');
    expect(lastCall().url).toMatch(/\/api\/v1\/diagnostics\/KAFKA\/attempts$/);
    expect(lastCall().init.method).toBe('POST');
    expect(firstKey).toBeTruthy();
    // A distinct learner action must not reuse the previous key.
    expect(secondKey).not.toBe(firstKey);
  });

  it('carries a per-action interactionId on every request', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(jsonResponse({ recommendations: [] }));

    await getRecommendations();
    const { interaction } = lastCall();

    expect(interaction.interactionId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
  });

  it('builds version-scoped read URLs', async () => {
    vi.mocked(authenticatedFetch).mockImplementation(async () =>
      jsonResponse({ domainCode: 'KAFKA', versionCode: 'v1', skills: [] }),
    );
    await getMasteryMap();
    expect(lastCall().url).toMatch(/\/api\/v1\/me\/mastery\/KAFKA\/versions\/v1$/);

    vi.mocked(authenticatedFetch).mockImplementation(async () =>
      jsonResponse({ attemptId: 'a1', status: 'IN_PROGRESS', items: [] }),
    );
    await getAttempt('a1');
    expect(lastCall().url).toMatch(/\/api\/v1\/diagnostics\/KAFKA\/attempts\/a1$/);
  });

  it('reads the latest authorized feedback without issuing a command', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(
      jsonResponse({ status: 'UNAVAILABLE', approvedFeedback: null }),
    );

    await getAssessmentFeedback();
    const { url, init } = lastCall();

    expect(url).toMatch(/\/api\/v1\/me\/assessment-evaluations\/latest-feedback$/);
    expect(init.method).toBe('GET');
    expect(init.body).toBeUndefined();
  });

  it('submits responses as JSON', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(
      jsonResponse({ attemptId: 'a1', status: 'COMPLETED', scoringVersion: 'v', itemsAnswered: 1, skillScores: [] }),
    );

    await submitDiagnostic('a1', [{ itemId: 'i1', selectedOptions: ['B'] }]);
    const { url, init } = lastCall();

    expect(url).toMatch(/\/attempts\/a1\/submit$/);
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
    expect(JSON.parse(String(init.body))).toEqual({
      responses: [{ itemId: 'i1', selectedOptions: ['B'] }],
    });
  });

  it('translates a failed response into a displayable error with a support code', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(
      jsonResponse(
        { code: 'GOAL_NOT_SET', title: 'Learning goal not set', interactionId: 'int-77' },
        404,
      ),
    );

    await expect(getRecommendations()).rejects.toSatisfy((error: unknown) => {
      expect(error).toBeInstanceOf(RamalsApiError);
      const apiError = error as RamalsApiError;
      expect(apiError.code).toBe('GOAL_NOT_SET');
      expect(apiError.supportCode).toBe('int-77');
      expect(apiError.httpStatus).toBe(404);
      return true;
    });
  });

  it('falls back to the request interactionId when the server body carries none', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(new Response('', { status: 500 }));

    await expect(getMasteryMap()).rejects.toSatisfy((error: unknown) => {
      const apiError = error as RamalsApiError;
      expect(apiError.code).toBe('UNKNOWN_ERROR');
      // Support still gets a correlatable code even when the server returned nothing usable.
      expect(apiError.supportCode).toBeTruthy();
      return true;
    });
  });
});
