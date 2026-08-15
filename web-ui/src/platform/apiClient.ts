import { INTERACTION_ID_HEADER, createInteractionId } from './correlation';

export interface Interaction {
  readonly interactionId: string;
}

export function beginInteraction(): Interaction {
  return { interactionId: createInteractionId() };
}

export async function interactionFetch(
  interaction: Interaction,
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set(INTERACTION_ID_HEADER, interaction.interactionId);
  return fetch(input, { ...init, headers });
}

