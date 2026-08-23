import { useCallback, useEffect, useState } from 'react';
import { ErrorBanner } from '../components/ErrorBanner';
import { DiagnosticPanel } from './DiagnosticPanel';
import { EvaluationFeedbackPanel } from './EvaluationFeedbackPanel';
import { MasteryMap } from './MasteryMap';
import { RecommendationsPanel } from './RecommendationsPanel';
import {
  getMasteryMap,
  getRecommendations,
  type MasterySkill,
  type Recommendation,
} from './api';

/** The learner's home: diagnostic, mastery map, and recommendations, refreshed after each submit. */
export function LearnerDashboard({ onLogout }: { onLogout: () => void }) {
  const [skills, setSkills] = useState<readonly MasterySkill[]>([]);
  const [recommendations, setRecommendations] = useState<readonly Recommendation[]>([]);
  const [error, setError] = useState<unknown>(null);

  const refresh = useCallback(async () => {
    setError(null);
    try {
      const [mastery, recs] = await Promise.all([getMasteryMap(), getRecommendations()]);
      setSkills(mastery.skills);
      setRecommendations(recs.recommendations);
    } catch (caught) {
      setError(caught);
    }
  }, []);

  useEffect(() => {
    // Load the learner's mastery and recommendations once on mount; the setState happens
    // asynchronously after the fetch resolves, not synchronously within the effect body.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, [refresh]);

  return (
    <main className="app">
      <header className="app-header">
        <div>
          <p className="eyebrow">RAMALS</p>
          <h1>Your Kafka learning</h1>
        </div>
        <button type="button" className="link-button" onClick={onLogout}>
          Log out
        </button>
      </header>

      {error != null && <ErrorBanner error={error} />}

      <DiagnosticPanel onCompleted={() => void refresh()} />
      <EvaluationFeedbackPanel />
      <MasteryMap skills={skills} />
      <RecommendationsPanel recommendations={recommendations} />
    </main>
  );
}
