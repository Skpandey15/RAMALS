import { describe, expect, it, vi } from 'vitest';
import { beginInteraction, interactionFetch } from './apiClient';
import { createInteractionId } from './correlation';

describe('correlation contract', () => {
  it('creates a canonical UUIDv7', () => {
    expect(createInteractionId(1_723_600_000_000)).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
  });

  it('preserves one interaction identifier across retries', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response());
    const interaction = beginInteraction();

    await interactionFetch(interaction, '/api/example', { method: 'POST' });
    await interactionFetch(interaction, '/api/example', { method: 'POST' });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const call of fetchMock.mock.calls) {
      const headers = new Headers(call[1]?.headers);
      expect(headers.get('X-Interaction-ID')).toBe(interaction.interactionId);
    }
    fetchMock.mockRestore();
  });
});

