import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MasteryMap } from './MasteryMap';
import { type MasterySkill } from './api';

function skill(overrides: Partial<MasterySkill> = {}): MasterySkill {
  return {
    skillCode: 'KAFKA-PARTITIONS',
    masteryScore: 0.5,
    evidenceConfidence: 0.5,
    masteryStatus: 'DEVELOPING',
    ...overrides,
  } as MasterySkill;
}

describe('MasteryMap', () => {
  it('tells a learner with no mastery what to do rather than showing an empty table', () => {
    render(<MasteryMap skills={[]} />);

    expect(screen.getByText(/Complete the diagnostic to build your first mastery map/)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('renders one row per skill with its score, confidence and status', () => {
    render(
      <MasteryMap
        skills={[
          skill({ skillCode: 'KAFKA-PARTITIONS', masteryScore: 0.82, evidenceConfidence: 0.64, masteryStatus: 'MASTERED' }),
          skill({ skillCode: 'KAFKA-CONSUMERS', masteryScore: 0.31, evidenceConfidence: 0.9, masteryStatus: 'DEVELOPING' }),
        ]}
      />,
    );

    const partitions = screen.getByRole('row', { name: /KAFKA-PARTITIONS/ });
    expect(within(partitions).getByText('MASTERED')).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(3); // header + two skills
  });

  it('formats scores to two decimals so columns stay comparable at a glance', () => {
    render(<MasteryMap skills={[skill({ masteryScore: 0.8, evidenceConfidence: 0.666666 })]} />);

    // 0.8 must read as "0.80", not "0.8": a ragged column is harder to scan than a padded one, and
    // the trailing zero is the difference between the two.
    expect(screen.getByText('0.80')).toBeInTheDocument();
    expect(screen.getByText('0.67')).toBeInTheDocument();
  });

  it('labels the table for screen readers', () => {
    render(<MasteryMap skills={[skill()]} />);

    expect(screen.getByRole('table')).toHaveAccessibleName(/Mastery score, evidence confidence, and status/);
  });
});
