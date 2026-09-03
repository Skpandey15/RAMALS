import { useState } from 'react';
import { ErrorBanner } from '../components/ErrorBanner';
import {
  getAttempt,
  startDiagnostic,
  submitDiagnostic,
  type AttemptDetail,
  type SubmissionResult,
} from './api';

/**
 * The result of one diagnostic: how each skill scored, which ones came out weakest, and what the
 * score does and does not yet establish.
 *
 * <p>The last part matters. A diagnostic sets a starting hypothesis; it does not confer mastery.
 * A learner who answers everything correctly and then sees INSUFFICIENT_EVIDENCE on the mastery
 * map below will read that as a bug unless this panel has already told them why one question per
 * skill is not enough evidence to trust a score, however good the score is.
 */
function DiagnosticResult({ result }: { result: SubmissionResult }) {
  const scores = result.skillScores ?? [];
  const correct = scores.reduce((total, score) => total + score.itemsCorrect, 0);
  const weakest = scores.filter((score) => score.itemsCorrect < score.itemsAnswered);

  return (
    <div className="result">
      <h3>Diagnostic complete</h3>
      <p>
        You answered {correct} of {result.itemsAnswered} correctly.
      </p>

      {scores.length > 0 && (
        <table className="mastery-table">
          <caption className="visually-hidden">Diagnostic score per skill</caption>
          <thead>
            <tr>
              <th scope="col">Skill</th>
              <th scope="col">Correct</th>
            </tr>
          </thead>
          <tbody>
            {scores.map((score) => (
              <tr key={score.skillCode}>
                <th scope="row">{score.skillCode}</th>
                <td>
                  {score.itemsCorrect} / {score.itemsAnswered}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {weakest.length > 0 && (
        <p>
          Weakest so far: {weakest.map((score) => score.skillCode).join(', ')}.
        </p>
      )}

      <p>
        This is a starting point, not a verdict. Your mastery map and recommended next steps below
        have been updated — skills still marked INSUFFICIENT_EVIDENCE need more practice before
        the score behind them can be trusted.
      </p>
    </div>
  );
}

/** Drives the curated Kafka diagnostic: start an attempt, answer items, submit for scoring. */
export function DiagnosticPanel({ onCompleted }: { onCompleted: () => void }) {
  const [attempt, setAttempt] = useState<AttemptDetail | null>(null);
  const [selections, setSelections] = useState<Record<string, string>>({});
  const [result, setResult] = useState<SubmissionResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function begin() {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const summary = await startDiagnostic();
      setAttempt(await getAttempt(summary.attemptId));
      setSelections({});
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  async function submit() {
    if (!attempt) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const responses = attempt.items.map((item) => ({
        itemId: item.itemId,
        selectedOptions: [selections[item.itemId]],
      }));
      setResult(await submitDiagnostic(attempt.attemptId, responses));
      setAttempt(null);
      onCompleted();
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  const answered = attempt ? attempt.items.every((item) => selections[item.itemId]) : false;

  return (
    <section aria-labelledby="diagnostic-heading" className="panel">
      <h2 id="diagnostic-heading">Kafka diagnostic</h2>
      {error != null && <ErrorBanner error={error} />}

      {!attempt && (
        <button type="button" onClick={begin} disabled={busy}>
          {busy ? 'Starting…' : 'Start diagnostic'}
        </button>
      )}

      {attempt && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <ol className="items">
            {attempt.items.map((item) => (
              <li key={item.itemId}>
                <fieldset>
                  <legend>{item.stem}</legend>
                  {item.options.map((option) => (
                    <label key={option.id} className="option">
                      <input
                        type="radio"
                        name={item.itemId}
                        value={option.id}
                        checked={selections[item.itemId] === option.id}
                        onChange={() =>
                          setSelections((current) => ({ ...current, [item.itemId]: option.id }))
                        }
                      />
                      {option.text}
                    </label>
                  ))}
                </fieldset>
              </li>
            ))}
          </ol>
          <button type="submit" disabled={busy || !answered}>
            {busy ? 'Submitting…' : 'Submit diagnostic'}
          </button>
        </form>
      )}

      {result && <DiagnosticResult result={result} />}
    </section>
  );
}
