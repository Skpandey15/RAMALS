import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./auth/authClient', () => ({
  isAuthenticated: vi.fn(),
  hasRealmRole: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  authenticatedFetch: vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ onboardingState: 'ONBOARDED', nextStep: 'COMPLETE' }),
  }),
}));

vi.mock('./learning/api', () => ({
  startDiagnostic: vi.fn(),
  getAttempt: vi.fn(),
  submitDiagnostic: vi.fn(),
  getMasteryMap: vi.fn().mockResolvedValue({ domainCode: 'KAFKA', versionCode: 'v1', skills: [] }),
  getRecommendations: vi.fn().mockResolvedValue({ recommendations: [] }),
  getAssessmentFeedback: vi.fn().mockResolvedValue({
    status: 'UNAVAILABLE',
    approvedFeedback: null,
  }),
}));

import { App } from './App';
import { authenticatedFetch, hasRealmRole, isAuthenticated } from './auth/authClient';

describe('App auth gate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(hasRealmRole).mockReturnValue(false);
    window.history.replaceState(null, '', '/');
  });

  it.each(['/register', '/register/'])('renders registration at %s', (path) => {
    window.history.replaceState(null, '', path);
    render(<App />);
    expect(screen.getByRole('heading', { name: /create your learner account/i })).toBeInTheDocument();
    expect(isAuthenticated).not.toHaveBeenCalled();
  });

  it('prompts unauthenticated visitors to log in', () => {
    vi.mocked(isAuthenticated).mockReturnValue(false);
    render(<App />);
    expect(screen.getByRole('button', { name: /log in/i })).toBeInTheDocument();
  });

  it('routes an authenticated ADMIN directly to the admin dashboard', () => {
    vi.mocked(isAuthenticated).mockReturnValue(true);
    vi.mocked(hasRealmRole).mockImplementation((role) => role === 'ADMIN');

    render(<App />);

    expect(screen.getByRole('heading', { name: /admin dashboard/i })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /your kafka learning/i })).not.toBeInTheDocument();
    expect(authenticatedFetch).not.toHaveBeenCalled();
  });

  it('keeps non-admin authenticated users behind learner onboarding', async () => {
    vi.mocked(isAuthenticated).mockReturnValue(true);
    render(<App />);
    expect(
      await screen.findByRole('heading', { name: /your kafka learning/i }),
    ).toBeInTheDocument();
  });
});
