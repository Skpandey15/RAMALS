import { type Recommendation } from './api';

const ACTION_LABELS: Record<string, string> = {
  COLLECT_EVIDENCE: 'Collect more evidence',
  RETEACH: 'Re-teach',
  PRACTICE: 'Practice',
  ADVANCE: 'Advance',
};

const REASON_LABELS: Record<string, string> = {
  INSUFFICIENT_EVIDENCE: 'Not enough evidence yet',
  BELOW_RETEACH_BOUNDARY: 'Well below the mastery threshold',
  BELOW_PRACTICE_BOUNDARY: 'Below the mastery threshold',
  APPROACHING_THRESHOLD: 'Approaching the mastery threshold',
  PROVISIONALLY_MASTERED_LOW_CONFIDENCE: 'Looks mastered, needs confirming evidence',
  MASTERED: 'Mastered',
};

/** Shows the deterministic next-best action per skill, with a plain-language reason. */
export function RecommendationsPanel({
  recommendations,
}: {
  recommendations: readonly Recommendation[];
}) {
  return (
    <section aria-labelledby="recommendations-heading" className="panel">
      <h2 id="recommendations-heading">Recommended next steps</h2>
      {recommendations.length === 0 ? (
        <p>No recommendations yet. Complete the diagnostic to get your next steps.</p>
      ) : (
        <ul className="recommendations">
          {recommendations.map((recommendation) => (
            <li key={recommendation.skillCode}>
              <span className="skill">{recommendation.skillCode}</span>
              <span className="action">
                {ACTION_LABELS[recommendation.recommendedAction] ?? recommendation.recommendedAction}
              </span>
              <span className="reason">
                {REASON_LABELS[recommendation.reasonCode] ?? recommendation.reasonCode}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
