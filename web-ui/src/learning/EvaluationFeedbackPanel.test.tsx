import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RamalsApiError } from '../platform/apiClient';

vi.mock('./api', () => ({
  getAssessmentFeedback: vi.fn(),
}));

import { EvaluationFeedbackPanel } from './EvaluationFeedbackPanel';
import { getAssessmentFeedback } from './api';

const APPROVED = {
  status: 'EVALUATED' as const,
  approvedFeedback: {
    answerVersion: 'answer-v1',
    rubricVersion: 'rubric-v1',
    feedback: 'Explain how acknowledgements change durability.',
    rubricResults: [
      {
        dimensionId: 'accuracy',
        score: 2,
        maxScore: 4,
        feedback: 'The main distinction is present but needs precision.',
      },
    ],
    nextLearningRationale: 'Focus next on accuracy; it has the greatest remaining opportunity.',
    evaluatedAt: '2026-08-23T00:00:00Z',
  },
};

describe('EvaluationFeedbackPanel', () => {
  beforeEach(() => vi.clearAllMocks());

  it('announces pending state while the asynchronous read is incomplete', () => {
    vi.mocked(getAssessmentFeedback).mockReturnValue(new Promise(() => undefined));
    render(<EvaluationFeedbackPanel />);

    expect(screen.getByRole('status')).toHaveTextContent(/being checked/i);
    expect(screen.getByRole('button', { name: /refresh feedback/i })).toBeDisabled();
  });

  it('shows only approved feedback, rubric results, and next-learning rationale', async () => {
    vi.mocked(getAssessmentFeedback).mockResolvedValue(APPROVED);
    render(<EvaluationFeedbackPanel />);

    expect(await screen.findByText(APPROVED.approvedFeedback.feedback)).toBeInTheDocument();
    const rubric = screen.getByRole('table', { name: /approved rubric results/i });
    expect(within(rubric).getByRole('rowheader', { name: 'accuracy' })).toBeInTheDocument();
    expect(screen.getByText(APPROVED.approvedFeedback.nextLearningRationale)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/trace|prompt|policy|confidence/i);
  });

  it.each([
    ['REJECTED', /not approved/i],
    ['MANUAL_REVIEW', /needs review/i],
    ['UNAVAILABLE', /no approved evaluation feedback/i],
  ] as const)('renders the %s state without model content', async (status, expected) => {
    vi.mocked(getAssessmentFeedback).mockResolvedValue({ status, approvedFeedback: null });
    render(<EvaluationFeedbackPanel />);

    expect(await screen.findByText(expected)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('unsafe model feedback');
  });

  it('refreshes with a read-only request and recovers after a transient failure', async () => {
    vi.mocked(getAssessmentFeedback)
      .mockRejectedValueOnce(new RamalsApiError('UNEXPECTED_ERROR', 'support-1', 503, 'Try again.'))
      .mockResolvedValueOnce(APPROVED);
    render(<EvaluationFeedbackPanel />);

    expect(await screen.findByRole('alert')).toHaveTextContent('support-1');
    fireEvent.click(screen.getByRole('button', { name: /refresh feedback/i }));

    expect(await screen.findByText(APPROVED.approvedFeedback.feedback)).toBeInTheDocument();
    expect(getAssessmentFeedback).toHaveBeenCalledTimes(2);
  });

  it('aborts the currently active refreshed request when unmounted', async () => {
    let refreshedSignal: AbortSignal | undefined;
    vi.mocked(getAssessmentFeedback)
      .mockResolvedValueOnce({ status: 'UNAVAILABLE', approvedFeedback: null })
      .mockImplementationOnce((signal) => {
        refreshedSignal = signal;
        return new Promise(() => undefined);
      });
    const { unmount } = render(<EvaluationFeedbackPanel />);

    await screen.findByText(/no approved evaluation feedback/i);
    fireEvent.click(screen.getByRole('button', { name: /refresh feedback/i }));
    await waitFor(() => expect(refreshedSignal).toBeDefined());

    unmount();

    expect(refreshedSignal?.aborted).toBe(true);
  });
});
