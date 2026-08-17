import { useCallback, useRef, useState } from 'react';
import { type TutorOutcome, requestTutorExplanation } from './tutorApi';

/**
 * Plain-language reasons. The learner is told what happened without being told about circuits,
 * bulkheads or deadlines, all of which are true and none of which are theirs to act on.
 *
 * Every operational reason produces the same sentence deliberately: from the learner's side,
 * "temporarily unavailable" is the whole of the actionable truth, and the distinction that matters
 * to an operator is carried in the metrics and logs rather than on screen.
 */
const UNAVAILABLE_MESSAGES: Record<string, string> = {
  AI_NOT_CONFIGURED: 'Explanations are not enabled here. Everything else works as usual.',
  UNKNOWN_SKILL: 'That skill is not part of your curriculum.',
  AI_CIRCUIT_OPEN: 'Explanations are temporarily unavailable. Everything else works as usual.',
  AI_BULKHEAD_FULL: 'Explanations are temporarily unavailable. Everything else works as usual.',
  AI_TRANSPORT_FAILURE:
    'Explanations are temporarily unavailable. Everything else works as usual.',
  AI_DEADLINE_EXCEEDED:
    'That took longer than expected, so it was stopped. You can try again.',
};

type PanelState =
  | { readonly status: 'idle' }
  | { readonly status: 'pending'; readonly supportCode: string }
  | { readonly status: 'answered'; readonly outcome: TutorOutcome }
  | { readonly status: 'cancelled' };

/**
 * Asks the tutor to explain one skill.
 *
 * M1-ADR-004 decided the tutor does not stream, so this must make a wait of up to eight seconds
 * tolerable without showing content: an explicit pending state, the support code visible from the
 * moment the request starts rather than only on failure, and a cancel that actually abandons the
 * request rather than hiding it.
 *
 * Nothing is written to browser storage. The support code lives in component state and disappears
 * with the page, which is the correct lifetime for it — it identifies one interaction, not a
 * session, and `storagePolicy.test.ts` enforces the rule repository-wide.
 */
export function TutorPanel({ skillCode, masteryStatus }: { skillCode: string; masteryStatus: string }) {
  const [state, setState] = useState<PanelState>({ status: 'idle' });
  const abortRef = useRef<AbortController | null>(null);

  const ask = useCallback(async () => {
    const controller = new AbortController();
    abortRef.current = controller;

    // The support code exists before the request does, so a learner who waits and then gives up
    // still has something to quote. Producing it only on failure would mean the slowest, most
    // frustrating cases are the ones with nothing to report.
    const started = requestTutorExplanation(skillCode, masteryStatus, controller.signal);
    setState({ status: 'pending', supportCode: started.supportCode });

    try {
      const outcome = await started.result;
      setState({ status: 'answered', outcome });
    } catch (failure) {
      if (controller.signal.aborted) {
        setState({ status: 'cancelled' });
        return;
      }
      setState({
        status: 'answered',
        outcome: {
          kind: 'unavailable',
          reason: 'AI_TRANSPORT_FAILURE',
          supportCode: started.supportCode,
        },
      });
    } finally {
      abortRef.current = null;
    }
  }, [skillCode, masteryStatus]);

  const cancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  return (
    <section aria-labelledby="tutor-heading" className="panel">
      <h2 id="tutor-heading">Explanation</h2>

      {state.status === 'idle' && (
        <button type="button" onClick={ask}>
          Explain {skillCode}
        </button>
      )}

      {state.status === 'pending' && (
        <div>
          {/* Polite rather than assertive: this announces once when it settles instead of
              interrupting a screen reader mid-sentence on every render. */}
          <p role="status" aria-live="polite">
            Preparing an explanation. This can take a few seconds.
          </p>
          <p className="support-code">
            Reference: <code>{state.supportCode}</code>
          </p>
          <button type="button" onClick={cancel}>
            Cancel
          </button>
        </div>
      )}

      {state.status === 'cancelled' && (
        <div>
          <p role="status" aria-live="polite">
            Cancelled. Nothing was changed.
          </p>
          <button type="button" onClick={ask}>
            Try again
          </button>
        </div>
      )}

      {state.status === 'answered' && state.outcome.kind === 'proposed' && (
        <div>
          <p>{state.outcome.explanation}</p>
          {state.outcome.checksForUnderstanding.length > 0 && (
            <>
              <h3>Check your understanding</h3>
              <ul>
                {state.outcome.checksForUnderstanding.map((check) => (
                  <li key={check}>{check}</li>
                ))}
              </ul>
            </>
          )}
          {/* Stated on every explanation, not only when something looks wrong. A learner should
              never have to guess whether an explanation changed their record. */}
          <p className="advisory">
            This explanation is a suggestion. It does not change your mastery or progress.
          </p>
        </div>
      )}

      {state.status === 'answered' && state.outcome.kind === 'unavailable' && (
        <div>
          <p role="alert">
            {UNAVAILABLE_MESSAGES[state.outcome.reason] ??
              'Explanations are temporarily unavailable. Everything else works as usual.'}
          </p>
          <p className="support-code">
            Reference: <code>{state.outcome.supportCode}</code>
          </p>
          <button type="button" onClick={ask}>
            Try again
          </button>
        </div>
      )}
    </section>
  );
}
