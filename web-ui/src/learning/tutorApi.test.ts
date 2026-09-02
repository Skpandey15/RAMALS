import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../auth/authClient', () => ({
  authenticatedFetch: vi.fn(),
}));

import { authenticatedFetch } from '../auth/authClient';
import { requestTutorExplanation } from './tutorApi';

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function start(signal = new AbortController().signal) {
  return requestTutorExplanation('KAFKA-PARTITIONS', 'DEVELOPING', signal);
}

describe('tutor API client', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns the support code before the request resolves', () => {
    // The whole point of the split return (M1-ADR-004): a learner who waits and gives up must
    // already have the identifier to quote. Deliberately not awaited.
    vi.mocked(authenticatedFetch).mockReturnValue(new Promise(() => {}));

    const started = start();

    expect(started.supportCode).toBeTruthy();
  });

  it('reports a proposed explanation with its checks for understanding', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(
      jsonResponse({
        outcome: 'PROPOSED',
        explanation: 'A partition is an ordered, immutable log.',
        checksForUnderstanding: ['What guarantees ordering?', 'Why immutable?'],
        supportCode: 'int-server',
      }),
    );

    const outcome = await start().result;

    expect(outcome.kind).toBe('proposed');
    expect(outcome).toMatchObject({
      explanation: 'A partition is an ordered, immutable log.',
      checksForUnderstanding: ['What guarantees ordering?', 'Why immutable?'],
      supportCode: 'int-server',
    });
  });

  it('treats a degraded response as an outcome, not a thrown error', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(
      jsonResponse({ outcome: 'UNAVAILABLE', reason: 'AI_BUDGET_EXHAUSTED', supportCode: 'int-9' }),
    );

    // The platform degrades tutoring on purpose. Throwing would push every caller into a catch
    // block for something that is a normal, expected answer.
    const outcome = await start().result;

    expect(outcome).toEqual({ kind: 'unavailable', reason: 'AI_BUDGET_EXHAUSTED', supportCode: 'int-9' });
  });

  it('maps a non-OK response to unavailable rather than rejecting', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(
      jsonResponse({ reason: 'AI_TIMEOUT', supportCode: 'int-7' }, 503),
    );

    await expect(start().result).resolves.toEqual({
      kind: 'unavailable',
      reason: 'AI_TIMEOUT',
      supportCode: 'int-7',
    });
  });

  it('falls back to the client support code when the server sends none', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(new Response('not json', { status: 500 }));

    const started = start();
    const outcome = await started.result;

    // An unparseable error body must still leave the learner with the identifier they were given
    // up front, or the support code stops being a reliable way to trace the request.
    expect(outcome).toEqual({
      kind: 'unavailable',
      reason: 'AI_TRANSPORT_FAILURE',
      supportCode: started.supportCode,
    });
  });

  it('discards non-string entries in checksForUnderstanding', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(
      jsonResponse({
        outcome: 'PROPOSED',
        explanation: 'Partitions parallelise a topic.',
        checksForUnderstanding: ['Real check', 42, null, { nested: true }],
      }),
    );

    const outcome = await start().result;

    // The AI plane is a proposer, so its payload is untrusted shape as well as untrusted content.
    expect(outcome).toMatchObject({ checksForUnderstanding: ['Real check'] });
  });

  it('threads the abort signal so cancelling truly abandons the request', async () => {
    vi.mocked(authenticatedFetch).mockResolvedValue(jsonResponse({ outcome: 'PROPOSED', explanation: 'x' }));
    const controller = new AbortController();

    await start(controller.signal).result;

    const [, , init] = vi.mocked(authenticatedFetch).mock.calls[0];
    expect(init?.signal).toBe(controller.signal);
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({
      skillCode: 'KAFKA-PARTITIONS',
      masteryStatus: 'DEVELOPING',
    });
  });

  it('mints a distinct support code per request', () => {
    vi.mocked(authenticatedFetch).mockReturnValue(new Promise(() => {}));

    expect(start().supportCode).not.toBe(start().supportCode);
  });
});
