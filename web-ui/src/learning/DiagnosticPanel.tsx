import { useState } from 'react';
import { ErrorBanner } from '../components/ErrorBanner';
import {
  getAttempt,
  startDiagnostic,
  submitDiagnostic,
  type AttemptDetail,
  type SubmissionResult,
} from './api';

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

      {result && (
        <div className="result">
          <h3>Diagnostic complete</h3>
          <p>Scored {result.itemsAnswered} item(s) with {result.scoringVersion}.</p>
        </div>
      )}
    </section>
  );
}
