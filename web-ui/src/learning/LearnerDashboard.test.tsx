import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RamalsApiError } from '../platform/apiClient';

vi.mock('./api', () => ({
  DOMAIN: 'KAFKA',
  VERSION: 'v1',
  startDiagnostic: vi.fn(),
  getAttempt: vi.fn(),
  submitDiagnostic: vi.fn(),
  getMasteryMap: vi.fn(),
  getRecommendations: vi.fn(),
}));

import { LearnerDashboard } from './LearnerDashboard';
import * as api from './api';

const EMPTY_MASTERY = { domainCode: 'KAFKA', versionCode: 'v1', skills: [] };
const EMPTY_RECS = { recommendations: [] };

const BROKER_MASTERY = {
  domainCode: 'KAFKA',
  versionCode: 'v1',
  skills: [
    {
      skillCode: 'KAFKA_BROKER',
      masteryScore: 1,
      evidenceConfidence: 0.33,
      masteryStatus: 'INSUFFICIENT_EVIDENCE',
      aggregateVersion: 1,
    },
  ],
};

const BROKER_RECS = {
  recommendations: [
    {
      skillCode: 'KAFKA_BROKER',
      recommendedAction: 'COLLECT_EVIDENCE',
      reasonCode: 'INSUFFICIENT_EVIDENCE',
      masteryStatus: 'INSUFFICIENT_EVIDENCE',
      decisionRecordId: 'decision-1',
      createdAt: '2026-08-15T00:00:00Z',
    },
  ],
};

const ATTEMPT = {
  attemptId: 'attempt-1',
  status: 'IN_PROGRESS',
  items: [
    {
      itemId: 'item-1',
      itemCode: 'KAFKA_DIAG_BROKER',
      skillCode: 'KAFKA_BROKER',
      itemType: 'SINGLE_CHOICE',
      stem: 'Which responsibility belongs to a Kafka broker?',
      options: [
        { id: 'A', text: 'Rendering the UI' },
        { id: 'B', text: 'Storing partition log segments' },
      ],
      displayOrder: 1,
    },
  ],
};

describe('LearnerDashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('completes the diagnostic to mastery to recommendation slice', async () => {
    vi.mocked(api.getMasteryMap).mockResolvedValueOnce(EMPTY_MASTERY);
    vi.mocked(api.getRecommendations).mockResolvedValueOnce(EMPTY_RECS);
    render(<LearnerDashboard onLogout={vi.fn()} />);
    await screen.findByRole('heading', { name: /your kafka learning/i });

    vi.mocked(api.startDiagnostic).mockResolvedValue({ attemptId: 'attempt-1', status: 'IN_PROGRESS' });
    vi.mocked(api.getAttempt).mockResolvedValue(ATTEMPT);
    fireEvent.click(screen.getByRole('button', { name: /start diagnostic/i }));

    const correct = await screen.findByRole('radio', { name: 'Storing partition log segments' });
    fireEvent.click(correct);

    vi.mocked(api.submitDiagnostic).mockResolvedValue({
      attemptId: 'attempt-1',
      status: 'COMPLETED',
      scoringVersion: 'DIAGNOSTIC_SCORING_V1',
      itemsAnswered: 1,
      skillScores: [
        { skillCode: 'KAFKA_BROKER', itemsAnswered: 1, itemsCorrect: 1, observedScore: 1, normalizedScore: 1 },
      ],
    });
    vi.mocked(api.getMasteryMap).mockResolvedValueOnce(BROKER_MASTERY);
    vi.mocked(api.getRecommendations).mockResolvedValueOnce(BROKER_RECS);
    fireEvent.click(screen.getByRole('button', { name: /submit diagnostic/i }));

    expect(await screen.findByText(/diagnostic complete/i)).toBeInTheDocument();
    const masteryTable = await screen.findByRole('table');
    expect(within(masteryTable).getByRole('rowheader', { name: 'KAFKA_BROKER' })).toBeInTheDocument();
    expect(within(masteryTable).getByText('INSUFFICIENT_EVIDENCE')).toBeInTheDocument();
    expect(await screen.findByText(/collect more evidence/i)).toBeInTheDocument();
  });

  it('is accessible: headings, labelled radios, and a captioned mastery table', async () => {
    vi.mocked(api.getMasteryMap).mockResolvedValueOnce(BROKER_MASTERY);
    vi.mocked(api.getRecommendations).mockResolvedValueOnce(BROKER_RECS);
    render(<LearnerDashboard onLogout={vi.fn()} />);

    expect(screen.getByRole('heading', { level: 1, name: /your kafka learning/i })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: /kafka diagnostic/i })).toBeInTheDocument();
    expect(await screen.findByRole('table')).toHaveAccessibleName(/mastery score/i);
  });

  it('surfaces a support code on failure without exposing tokens', async () => {
    vi.mocked(api.getMasteryMap).mockRejectedValueOnce(
      new RamalsApiError('UNEXPECTED_ERROR', 'support-abc-123', 500, 'The operation could not be completed.'),
    );
    vi.mocked(api.getRecommendations).mockResolvedValueOnce(EMPTY_RECS);
    render(<LearnerDashboard onLogout={vi.fn()} />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('support-abc-123');
    expect(alert).not.toHaveTextContent(/bearer/i);
  });
});
