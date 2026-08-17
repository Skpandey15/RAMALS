import { authenticatedFetch } from '../auth/authClient';
import { beginInteraction } from '../platform/apiClient';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/**
 * What the platform returned, mirroring the server's `TutorOutcome`.
 *
 * A discriminated union rather than a nullable explanation, for the same reason the Java side is a
 * sealed type: "no explanation" and "no explanation *because*" are different facts, and a UI that
 * cannot tell them apart cannot tell a learner whether to try again.
 */
export type TutorOutcome =
  | {
      readonly kind: 'proposed';
      readonly explanation: string;
      readonly checksForUnderstanding: readonly string[];
      readonly supportCode: string;
    }
  | {
      readonly kind: 'unavailable';
      readonly reason: string;
      readonly supportCode: string;
    };

export interface StartedTutorRequest {
  /** Available immediately, so a learner who waits and gives up still has something to quote. */
  readonly supportCode: string;
  readonly result: Promise<TutorOutcome>;
}

/**
 * Asks the platform for an explanation.
 *
 * Returns the support code synchronously and the outcome as a promise. That split exists because
 * M1-ADR-004 makes this a request that can take seconds with nothing to show: the identifier a
 * learner needs in order to report a problem must exist before the problem does.
 *
 * The `signal` is threaded to `fetch` so cancelling genuinely abandons the request rather than
 * hiding a response that still arrives.
 */
export function requestTutorExplanation(
  skillCode: string,
  masteryStatus: string,
  signal: AbortSignal,
): StartedTutorRequest {
  // One interaction per tutor request. It carries the correlation header on the wire and gives the
  // learner the support code to quote, which are the same identifier by design.
  const interaction = beginInteraction();
  const supportCode = interaction.interactionId;

  const result = (async (): Promise<TutorOutcome> => {
    const response = await authenticatedFetch(interaction, `${API_BASE_URL}/api/v1/tutor/explain`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ skillCode, masteryStatus }),
      signal,
    });

    if (!response.ok) {
      // A non-OK response is still an outcome, not an exception: the platform degrades tutoring on
      // purpose, and treating that as a thrown error would push every caller into a catch block.
      const body = await response.json().catch(() => ({}));
      return {
        kind: 'unavailable',
        reason: typeof body.reason === 'string' ? body.reason : 'AI_TRANSPORT_FAILURE',
        supportCode: typeof body.supportCode === 'string' ? body.supportCode : supportCode,
      };
    }

    const body = await response.json();
    if (body.outcome === 'UNAVAILABLE') {
      return {
        kind: 'unavailable',
        reason: typeof body.reason === 'string' ? body.reason : 'AI_TRANSPORT_FAILURE',
        supportCode: typeof body.supportCode === 'string' ? body.supportCode : supportCode,
      };
    }

    return {
      kind: 'proposed',
      explanation: typeof body.explanation === 'string' ? body.explanation : '',
      checksForUnderstanding: Array.isArray(body.checksForUnderstanding)
        ? body.checksForUnderstanding.filter((check: unknown): check is string => typeof check === 'string')
        : [],
      supportCode: typeof body.supportCode === 'string' ? body.supportCode : supportCode,
    };
  })();

  return { supportCode, result };
}
