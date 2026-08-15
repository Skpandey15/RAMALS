import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./auth/authClient', () => ({
  isAuthenticated: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('./learning/api', () => ({
  startDiagnostic: vi.fn(),
  getAttempt: vi.fn(),
  submitDiagnostic: vi.fn(),
  getMasteryMap: vi.fn().mockResolvedValue({ domainCode: 'KAFKA', versionCode: 'v1', skills: [] }),
  getRecommendations: vi.fn().mockResolvedValue({ recommendations: [] }),
}));

import { App } from './App';
import { isAuthenticated } from './auth/authClient';

describe('App auth gate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('prompts unauthenticated visitors to log in', () => {
    vi.mocked(isAuthenticated).mockReturnValue(false);
    render(<App />);
    expect(screen.getByRole('button', { name: /log in/i })).toBeInTheDocument();
  });

  it('shows the learner dashboard when authenticated', async () => {
    vi.mocked(isAuthenticated).mockReturnValue(true);
    render(<App />);
    expect(
      await screen.findByRole('heading', { name: /your kafka learning/i }),
    ).toBeInTheDocument();
  });
});
