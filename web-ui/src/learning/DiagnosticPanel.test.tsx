import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RamalsApiError } from '../platform/apiClient';

vi.mock('./api', () => ({
  startDiagnostic: vi.fn(),
  getAttempt: vi.fn(),
  submitDiagnostic: vi.fn(),
}));

import { getAttempt, startDiagnostic, submitDiagnostic } from './api';
import { DiagnosticPanel } from './DiagnosticPanel';

const ATTEMPT = {
  attemptId: 'attempt-1',
  status: 'IN_PROGRESS',
  items: [
    {
      itemId: 'item-1',
      stem: 'What orders records within a topic?',
      options: [
        { id: 'a', text: 'The partition' },
        { id: 'b', text: 'The consumer group' },
      ],
    },
    {
      itemId: 'item-2',
      stem: 'What does a consumer group provide?',
      options: [
        { id: 'a', text: 'Parallelism' },
        { id: 'b', text: 'Durability' },
      ],
    },
  ],
};

/** Starts an attempt and waits for its items, which every case below needs first. */
async function startAttempt() {
  fireEvent.click(screen.getByRole('button', { name: 'Start diagnostic' }));
  await screen.findByText('What orders records within a topic?');
}

describe('DiagnosticPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(startDiagnostic).mockResolvedValue({ attemptId: 'attempt-1', status: 'IN_PROGRESS' } as never);
    vi.mocked(getAttempt).mockResolvedValue(ATTEMPT as never);
    vi.mocked(submitDiagnostic).mockResolvedValue({
      itemsAnswered: 2,
      scoringVersion: 'scoring-v1',
      skillScores: [
        { skillCode: 'KAFKA_PARTITION', itemsAnswered: 1, itemsCorrect: 1 },
        { skillCode: 'KAFKA_CONSUMER_GROUPS', itemsAnswered: 1, itemsCorrect: 0 },
      ],
    } as never);
  });

  it('starts an attempt and renders its items', async () => {
    render(<DiagnosticPanel onCompleted={vi.fn()} />);

    await startAttempt();

    expect(screen.getByRole('group', { name: 'What does a consumer group provide?' })).toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(4);
  });

  it('keeps submit disabled until every item is answered', async () => {
    render(<DiagnosticPanel onCompleted={vi.fn()} />);
    await startAttempt();

    const submit = screen.getByRole('button', { name: 'Submit diagnostic' });
    expect(submit).toBeDisabled();

    // A partially answered attempt would be scored as if the blanks were wrong answers.
    fireEvent.click(screen.getByRole('radio', { name: 'The partition' }));
    expect(submit).toBeDisabled();

    fireEvent.click(screen.getByRole('radio', { name: 'Parallelism' }));
    expect(submit).toBeEnabled();
  });

  it('submits the selected option for each item and reports the score', async () => {
    const onCompleted = vi.fn();
    render(<DiagnosticPanel onCompleted={onCompleted} />);
    await startAttempt();

    fireEvent.click(screen.getByRole('radio', { name: 'The partition' }));
    fireEvent.click(screen.getByRole('radio', { name: 'Durability' }));
    fireEvent.click(screen.getByRole('button', { name: 'Submit diagnostic' }));

    await waitFor(() =>
      expect(submitDiagnostic).toHaveBeenCalledWith('attempt-1', [
        { itemId: 'item-1', selectedOptions: ['a'] },
        { itemId: 'item-2', selectedOptions: ['b'] },
      ]),
    );
    expect(await screen.findByText('You answered 1 of 2 correctly.')).toBeInTheDocument();
    // The per-skill breakdown is the point of the result: a total alone cannot tell a learner
    // which skill to work on next.
    expect(screen.getByRole('row', { name: /KAFKA_PARTITION/ })).toHaveTextContent('1 / 1');
    expect(screen.getByText(/Weakest so far: KAFKA_CONSUMER_GROUPS/)).toBeInTheDocument();
    // And it must say what the score does not establish, or INSUFFICIENT_EVIDENCE on the mastery
    // map below reads as a bug rather than as the design.
    expect(screen.getByText(/starting point, not a verdict/)).toBeInTheDocument();
    // The dashboard reloads mastery off this callback; without it the new score never appears.
    expect(onCompleted).toHaveBeenCalledOnce();
  });

  it('surfaces a failed start through the error banner and stays startable', async () => {
    vi.mocked(startDiagnostic).mockRejectedValue(
      new RamalsApiError('ATTEMPT_IN_PROGRESS', 'int-5', 409, 'You already have an attempt open.'),
    );
    render(<DiagnosticPanel onCompleted={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'Start diagnostic' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('You already have an attempt open.');
    expect(screen.getByText('int-5')).toBeInTheDocument();
    // The button must leave its busy state, or one failure is terminal for the session.
    expect(screen.getByRole('button', { name: 'Start diagnostic' })).toBeEnabled();
  });

  it('keeps the answered attempt on screen when submission fails', async () => {
    vi.mocked(submitDiagnostic).mockRejectedValue(
      new RamalsApiError('SCORING_UNAVAILABLE', 'int-6', 503, 'Scoring is unavailable.'),
    );
    render(<DiagnosticPanel onCompleted={vi.fn()} />);
    await startAttempt();
    fireEvent.click(screen.getByRole('radio', { name: 'The partition' }));
    fireEvent.click(screen.getByRole('radio', { name: 'Parallelism' }));

    fireEvent.click(screen.getByRole('button', { name: 'Submit diagnostic' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Scoring is unavailable.');
    // Discarding the attempt here would make a learner answer every item again for a server fault.
    expect(screen.getByText('What orders records within a topic?')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'The partition' })).toBeChecked();
  });
});
