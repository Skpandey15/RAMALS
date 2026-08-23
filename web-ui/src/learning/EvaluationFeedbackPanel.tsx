import { useCallback, useEffect, useRef, useState } from 'react';
import { ErrorBanner } from '../components/ErrorBanner';
import {
  getAssessmentFeedback,
  type AssessmentFeedback,
  type ApprovedEvaluationFeedback,
} from './api';

type FeedbackState =
  | { readonly kind: 'pending' }
  | { readonly kind: 'resolved'; readonly feedback: AssessmentFeedback }
  | { readonly kind: 'failed'; readonly error: unknown };

function ApprovedFeedback({ approved }: { approved: ApprovedEvaluationFeedback }) {
  return (
    <div className="evaluation-approved">
      <p>{approved.feedback}</p>
      <table className="rubric-table">
        <caption>Approved rubric results</caption>
        <thead>
          <tr>
            <th scope="col">Rubric area</th>
            <th scope="col">Result</th>
            <th scope="col">Feedback</th>
          </tr>
        </thead>
        <tbody>
          {approved.rubricResults.map((result) => (
            <tr key={result.dimensionId}>
              <th scope="row">{result.dimensionId}</th>
              <td>
                {result.score} / {result.maxScore}
              </td>
              <td>{result.feedback}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h3>Why this is your next step</h3>
      <p>{approved.nextLearningRationale}</p>
    </div>
  );
}

function ResolvedFeedback({ feedback }: { feedback: AssessmentFeedback }) {
  if (feedback.status === 'EVALUATED' && feedback.approvedFeedback != null) {
    return <ApprovedFeedback approved={feedback.approvedFeedback} />;
  }
  if (feedback.status === 'MANUAL_REVIEW') {
    return <p>Your evaluation needs review. No unapproved feedback is shown while it is checked.</p>;
  }
  if (feedback.status === 'REJECTED') {
    return <p>This evaluation was not approved, so its feedback is not shown.</p>;
  }
  return <p>No approved evaluation feedback is available yet.</p>;
}

/** Displays only the minimized, learner-authorized evaluation read model. */
export function EvaluationFeedbackPanel() {
  const [state, setState] = useState<FeedbackState>({ kind: 'pending' });
  const activeRequest = useRef<AbortController | null>(null);

  const refresh = useCallback(async () => {
    activeRequest.current?.abort();
    const controller = new AbortController();
    activeRequest.current = controller;
    setState({ kind: 'pending' });
    try {
      const feedback = await getAssessmentFeedback(controller.signal);
      if (!controller.signal.aborted) {
        setState({ kind: 'resolved', feedback });
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        setState({ kind: 'failed', error });
      }
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    activeRequest.current = controller;
    void getAssessmentFeedback(controller.signal)
      .then((feedback) => {
        if (!controller.signal.aborted) {
          setState({ kind: 'resolved', feedback });
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setState({ kind: 'failed', error });
        }
      });
    return () => activeRequest.current?.abort();
  }, []);

  return (
    <section aria-labelledby="evaluation-feedback-heading" className="panel">
      <div className="panel-heading-row">
        <h2 id="evaluation-feedback-heading">Evaluation feedback</h2>
        <button
          type="button"
          className="secondary-button"
          disabled={state.kind === 'pending'}
          onClick={() => void refresh()}
        >
          Refresh feedback
        </button>
      </div>

      <div aria-live="polite" aria-busy={state.kind === 'pending'}>
        {state.kind === 'pending' && <p role="status">Your evaluation feedback is being checked.</p>}
        {state.kind === 'resolved' && <ResolvedFeedback feedback={state.feedback} />}
        {state.kind === 'failed' && <ErrorBanner error={state.error} />}
      </div>
    </section>
  );
}
