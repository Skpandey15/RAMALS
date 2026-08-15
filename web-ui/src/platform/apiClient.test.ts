import { describe, expect, it } from 'vitest';
import { RamalsApiError, toApiError } from './apiClient';

describe('toApiError', () => {
  it('uses the problem interactionId as a display-safe support code', async () => {
    const response = new Response(
      JSON.stringify({ code: 'GOAL_NOT_SET', title: 'Not set', interactionId: 'int-123' }),
      { status: 404, headers: { 'Content-Type': 'application/json' } },
    );

    const error = await toApiError(response, 'fallback-id');

    expect(error).toBeInstanceOf(RamalsApiError);
    expect(error.supportCode).toBe('int-123');
    expect(error.code).toBe('GOAL_NOT_SET');
    expect(error.httpStatus).toBe(404);
  });

  it('falls back to the request interactionId when the body carries none', async () => {
    const response = new Response('', { status: 500 });

    const error = await toApiError(response, 'fallback-id');

    expect(error.supportCode).toBe('fallback-id');
    expect(error.code).toBe('UNKNOWN_ERROR');
  });
});
