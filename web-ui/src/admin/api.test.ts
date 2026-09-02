import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RamalsApiError } from '../platform/apiClient';

vi.mock('../auth/authClient', () => ({
  authenticatedFetch: vi.fn(),
}));

import { authenticatedFetch } from '../auth/authClient';
import {
  addIdentityRole,
  changeLearnerStatus,
  getOperationalSnapshot,
  listLearners,
  publishCurriculum,
  removeIdentityRole,
  retireCurriculum,
  setIdentityEnabled,
} from './api';

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** The (interaction, url, init) triple the admin client passed to the authenticated fetch. */
function lastCall() {
  const calls = vi.mocked(authenticatedFetch).mock.calls;
  const [interaction, url, init] = calls[calls.length - 1];
  return { interaction, url: String(url), init: init ?? {} };
}

describe('admin API client', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(authenticatedFetch).mockImplementation(async () => jsonResponse({}));
  });

  it('carries a fresh interactionId on every admin action', async () => {
    await listLearners();
    const first = lastCall().interaction.interactionId;
    await listLearners();
    const second = lastCall().interaction.interactionId;

    // Admin actions are audited by interactionId, so two actions sharing one would make the audit
    // trail ambiguous about which request did what.
    expect(first).toBeTruthy();
    expect(second).not.toBe(first);
  });

  it('reads the operational snapshot from the admin route', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(jsonResponse({ learnersTotal: 12 }));

    await expect(getOperationalSnapshot()).resolves.toMatchObject({ learnersTotal: 12 });
    expect(lastCall().url).toMatch(/\/api\/v1\/admin\/operations\/snapshot$/);
  });

  it('sends a status change as a PATCH carrying the new status', async () => {
    await changeLearnerStatus('learner-1', 'SUSPENDED');

    const { url, init } = lastCall();
    expect(url).toMatch(/\/api\/v1\/admin\/learners\/learner-1\/status$/);
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(String(init.body))).toEqual({ status: 'SUSPENDED' });
  });

  it('percent-encodes identifiers so one cannot escape its path segment', async () => {
    // A learner id is server-generated, but the client must not be the reason a crafted one could
    // reach a different admin route than the caller named.
    await changeLearnerStatus('a/../../evil', 'CLOSED');

    expect(lastCall().url).toContain('a%2F..%2F..%2Fevil');
    expect(lastCall().url).not.toContain('a/../../evil');
  });

  it('distinguishes publish from retire, and enable from disable', async () => {
    await publishCurriculum('cur-1');
    expect(lastCall().url).toMatch(/\/curricula\/cur-1\/publish$/);
    expect(lastCall().init.method).toBe('POST');

    await retireCurriculum('cur-1');
    expect(lastCall().url).toMatch(/\/curricula\/cur-1\/retire$/);

    await setIdentityEnabled('user-1', false);
    expect(JSON.parse(String(lastCall().init.body))).toEqual({ enabled: false });
  });

  it('separates granting a role from revoking it by HTTP method alone', async () => {
    await addIdentityRole('user-1', 'INSTRUCTOR');
    const granted = lastCall();

    await removeIdentityRole('user-1', 'INSTRUCTOR');
    const revoked = lastCall();

    // Same URL, opposite effect. If these ever converge on one method, a revoke becomes a grant.
    expect(granted.url).toBe(revoked.url);
    expect(granted.init.method).toBe('POST');
    expect(revoked.init.method).toBe('DELETE');
  });

  it('translates a non-OK response into a RamalsApiError', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(
      jsonResponse({ code: 'LEARNER_STATE_CONFLICT', title: 'Already suspended' }, 409),
    );

    await expect(changeLearnerStatus('learner-1', 'SUSPENDED')).rejects.toBeInstanceOf(RamalsApiError);
  });

  it('returns without parsing a body on 204', async () => {
    // Calling .json() on an empty body throws, so a no-content response must short-circuit.
    vi.mocked(authenticatedFetch).mockResolvedValue(new Response(null, { status: 204 }));

    await expect(listLearners()).resolves.toBeUndefined();
  });
});
