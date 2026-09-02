import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RecommendationsPanel } from './RecommendationsPanel';
import { type Recommendation } from './api';

function recommendation(overrides: Partial<Recommendation> = {}): Recommendation {
  return {
    skillCode: 'KAFKA-PARTITIONS',
    recommendedAction: 'PRACTICE',
    reasonCode: 'BELOW_PRACTICE_BOUNDARY',
    ...overrides,
  } as Recommendation;
}

describe('RecommendationsPanel', () => {
  it('tells a learner with no recommendations how to get some', () => {
    render(<RecommendationsPanel recommendations={[]} />);

    expect(screen.getByText(/Complete the diagnostic to get your next steps/)).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('translates engine codes into language a learner can act on', () => {
    render(
      <RecommendationsPanel
        recommendations={[
          recommendation({ recommendedAction: 'RETEACH', reasonCode: 'BELOW_RETEACH_BOUNDARY' }),
        ]}
      />,
    );

    expect(screen.getByText('Re-teach')).toBeInTheDocument();
    expect(screen.getByText('Well below the mastery threshold')).toBeInTheDocument();
    // The raw enum is the engine's vocabulary, not the learner's.
    expect(screen.queryByText('BELOW_RETEACH_BOUNDARY')).not.toBeInTheDocument();
  });

  it('shows an unmapped code verbatim rather than hiding it', () => {
    render(
      <RecommendationsPanel
        recommendations={[
          recommendation({ recommendedAction: 'REMEDIATE_LATER', reasonCode: 'SOME_NEW_REASON' }),
        ]}
      />,
    );

    // The engine's vocabulary can grow ahead of this table. Falling back to the raw code keeps the
    // panel truthful; rendering nothing would silently drop a recommendation the engine made.
    expect(screen.getByText('REMEDIATE_LATER')).toBeInTheDocument();
    expect(screen.getByText('SOME_NEW_REASON')).toBeInTheDocument();
  });

  it('renders every recommendation it is given', () => {
    render(
      <RecommendationsPanel
        recommendations={[
          recommendation({ skillCode: 'KAFKA-PARTITIONS' }),
          recommendation({ skillCode: 'KAFKA-CONSUMERS', recommendedAction: 'ADVANCE', reasonCode: 'MASTERED' }),
          recommendation({ skillCode: 'KAFKA-TOPICS', recommendedAction: 'COLLECT_EVIDENCE', reasonCode: 'INSUFFICIENT_EVIDENCE' }),
        ]}
      />,
    );

    expect(screen.getAllByRole('listitem')).toHaveLength(3);
    expect(screen.getByText('Mastered')).toBeInTheDocument();
    expect(screen.getByText('Not enough evidence yet')).toBeInTheDocument();
  });
});
