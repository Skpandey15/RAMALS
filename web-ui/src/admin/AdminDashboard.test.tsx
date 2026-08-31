import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./api', () => ({
  getOperationalSnapshot: vi.fn(),
  listLearners: vi.fn(),
  listCurricula: vi.fn(),
  listIdentities: vi.fn(),
  listAdminAudit: vi.fn(),
  listSecurityAudit: vi.fn(),
  changeLearnerStatus: vi.fn(),
  publishCurriculum: vi.fn(),
  retireCurriculum: vi.fn(),
  setIdentityEnabled: vi.fn(),
  addIdentityRole: vi.fn(),
  removeIdentityRole: vi.fn(),
}));

import { AdminDashboard } from './AdminDashboard';
import * as api from './api';

const learner = {
  learnerId: '01900000-0000-7000-8000-000000000111',
  subject: 'learner-subject',
  status: 'ACTIVE' as const,
  firstName: 'Ada',
  lastName: 'Learner',
  email: 'ada@example.test',
  mobile: '+919999999999',
  countryCode: 'IN',
  city: 'Bengaluru',
  emailVerified: true,
  mobileVerified: true,
  onboardingState: 'ONBOARDED',
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-31T00:00:00Z',
};

const snapshot = {
  learnersTotal: 4,
  learnersActive: 3,
  learnersSuspended: 1,
  learnersClosed: 0,
  learnersOnboarded: 2,
  curriculaDraft: 1,
  curriculaPublished: 2,
  curriculaRetired: 1,
  authorizationDenials24h: 5,
  adminActions24h: 6,
};

function primeReads() {
  vi.mocked(api.getOperationalSnapshot).mockResolvedValue(snapshot);
  vi.mocked(api.listLearners).mockResolvedValue([learner]);
  vi.mocked(api.listCurricula).mockResolvedValue([
    {
      curriculumVersionId: '01900000-0000-7000-8000-000000000002',
      domainCode: 'KAFKA',
      versionCode: 'v1',
      status: 'PUBLISHED',
      publishedAt: '2026-08-15T00:00:00Z',
    },
  ]);
  vi.mocked(api.listIdentities).mockResolvedValue([
    {
      id: 'staff-1',
      username: 'content-author',
      email: 'author@example.test',
      enabled: true,
      realmRoles: ['CONTENT_AUTHOR'],
    },
  ]);
  vi.mocked(api.listAdminAudit).mockResolvedValue([]);
  vi.mocked(api.listSecurityAudit).mockResolvedValue([]);
}

describe('AdminDashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    primeReads();
  });

  it('loads server-owned operational, learner, content and identity data', async () => {
    render(<AdminDashboard onLogout={vi.fn()} />);

    expect(await screen.findByText('Ada Learner')).toBeInTheDocument();
    expect(screen.getByText('content-author')).toBeInTheDocument();
    expect(screen.getByText('KAFKA')).toBeInTheDocument();
    expect(screen.getByText(/3 active/)).toBeInTheDocument();
    expect(api.getOperationalSnapshot).toHaveBeenCalledTimes(1);
    expect(api.listSecurityAudit).toHaveBeenCalledTimes(1);
  });

  it('uses the MFA-gated backend mutation contract for learner suspension', async () => {
    vi.mocked(api.changeLearnerStatus).mockResolvedValue({ ...learner, status: 'SUSPENDED' });
    render(<AdminDashboard onLogout={vi.fn()} />);

    const suspend = await screen.findByRole('button', { name: 'Suspend' });
    fireEvent.click(suspend);

    await waitFor(() =>
      expect(api.changeLearnerStatus).toHaveBeenCalledWith(learner.learnerId, 'SUSPENDED'),
    );
  });

  it('never offers staff-role mutation controls for learner identities', async () => {
    vi.mocked(api.listIdentities).mockResolvedValue([
      {
        id: 'learner-identity',
        username: 'learner',
        email: 'learner@example.test',
        enabled: true,
        realmRoles: ['LEARNER'],
      },
    ]);
    render(<AdminDashboard onLogout={vi.fn()} />);

    const addInstructor = await screen.findByRole('button', { name: 'Add instructor' });
    expect(addInstructor).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add content author' })).toBeDisabled();
  });
});
