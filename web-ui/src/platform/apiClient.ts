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

/**
 * A failed API call. `supportCode` is the interactionId the learner can quote to support; it never
 * contains tokens or other sensitive data, so it is safe to display.
 */
export class RamalsApiError extends Error {
  constructor(
    readonly code: string,
    readonly supportCode: string,
    readonly httpStatus: number,
    message: string,
  ) {
    super(message);
    this.name = 'RamalsApiError';
  }
}

/** Builds a display-safe error from a non-OK response, preferring the server's echoed interactionId. */
export async function toApiError(
  response: Response,
  fallbackInteractionId: string,
): Promise<RamalsApiError> {
  let problem: Record<string, unknown>;
  try {
    problem = (await response.json()) as Record<string, unknown>;
  } catch {
    problem = {};
  }
  const echoed = response.headers.get(INTERACTION_ID_HEADER) ?? undefined;
  const supportCode =
    typeof problem.interactionId === 'string' && problem.interactionId
      ? problem.interactionId
      : (echoed ?? fallbackInteractionId);
  const code = typeof problem.code === 'string' ? problem.code : 'UNKNOWN_ERROR';
  const title = typeof problem.title === 'string' ? problem.title : 'The request could not be completed.';
  // RFC 7807 splits the generic problem type (`title`) from the guidance specific to this occurrence
  // (`detail`). The detail is what tells a learner what to actually do next -- "wait for the cooldown",
  // "the code is not valid, request a new one" -- so prefer it, and fall back to the title for the
  // endpoints that only set one. The server decides how much a caller may learn; this only chooses
  // the more useful of the two fields it chose to send.
  const detail = typeof problem.detail === 'string' && problem.detail ? problem.detail : undefined;
  return new RamalsApiError(code, supportCode, response.status, detail ?? title);
}

