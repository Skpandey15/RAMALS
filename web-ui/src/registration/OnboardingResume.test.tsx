import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

vi.mock('../auth/authClient', () => ({ authenticatedFetch: vi.fn() }));

import { OnboardingResume } from './OnboardingResume';
import * as authClient from '../auth/authClient';

/**
 * Onboarding resumption.
 *
 * <p>The theme is that the server decides. This component may not admit a learner to the app on its
 * own belief, may not cache what it was told, and may not treat a failed lookup as permission.
 */

const authenticatedFetch = vi.mocked(authClient.authenticatedFetch);

function json(body: unknown, status = 200) {
  return Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

function onboarding(nextStep: string, overrides: Record<string, unknown> = {}) {
  return {
    onboardingState: 'MOBILE_PENDING',
    nextStep,
    emailVerified: true,
    mobileVerified: false,
    ...overrides,
  };
}

beforeEach(() => {
  authenticatedFetch.mockReset();
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

it('admits the learner to the app only once the server reports completion', async () => {
  authenticatedFetch.mockReturnValue(
    json(onboarding('COMPLETE', { onboardingState: 'ONBOARDED', mobileVerified: true })) as never,
  );
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  expect(await screen.findByText('dashboard')).toBeInTheDocument();
});

it('does not admit the learner while onboarding is incomplete', async () => {
  authenticatedFetch.mockReturnValue(json(onboarding('MOBILE_VERIFICATION')) as never);
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  await screen.findByText('Verify your mobile');
  expect(screen.queryByText('dashboard')).toBeNull();
});

it('fails closed when the onboarding lookup fails', async () => {
  authenticatedFetch.mockRejectedValue(new Error('network down'));
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  // Rendering the dashboard on a transient error would let a network failure do what a forged state
  // is not allowed to do.
  await screen.findByText('We could not load your account');
  expect(screen.queryByText('dashboard')).toBeNull();
});

it('fails closed when the onboarding lookup returns an error status', async () => {
  authenticatedFetch.mockReturnValue(json({ code: 'ACCESS_DENIED' }, 403) as never);
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  await screen.findByText('We could not load your account');
  expect(screen.queryByText('dashboard')).toBeNull();
});

it('routes a learner with no registration back to the public form', async () => {
  authenticatedFetch.mockReturnValue(
    json(onboarding('REGISTRATION', { onboardingState: 'NOT_REGISTERED', emailVerified: false })) as never,
  );
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  // A just-in-time provisioned learner is operationally ACTIVE and still has not onboarded.
  await screen.findByText('Finish creating your account');
  expect(screen.queryByText('dashboard')).toBeNull();
});

it('re-reads the server state after a successful verification rather than assuming it', async () => {
  authenticatedFetch
    .mockReturnValueOnce(json(onboarding('MOBILE_VERIFICATION')) as never)
    .mockReturnValueOnce(json({ challengeId: 'chal-1', resendAfter: '2026-01-01T00:00:00Z' }) as never)
    .mockReturnValueOnce(json({ onboardingState: 'PROFILE_PENDING', nextStep: 'PROFESSIONAL_PROFILE' }) as never)
    .mockReturnValueOnce(
      json(onboarding('PROFESSIONAL_PROFILE', { onboardingState: 'PROFILE_PENDING', mobileVerified: true })) as never,
    );

  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  fireEvent.click(await screen.findByRole('button', { name: 'Send code' }));
  const input = await screen.findByLabelText('Verification code');
  fireEvent.change(input, { target: { value: '123456' } });
  fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

  // The step after mobile verification is now the profile form rather than a message with nothing
  // to do: reaching PROFILE_PENDING must hand the learner something that advances them.
  await screen.findByText('Your professional background');
  // Four calls: the initial read, the send, the verify, and the re-read. Trusting the verify's 200
  // to mean a particular next state would put that decision back in the browser.
  await waitFor(() => expect(authenticatedFetch).toHaveBeenCalledTimes(4));
  expect(String(authenticatedFetch.mock.calls[3][1])).toContain('/api/v1/me/onboarding');
});

it('submits the profile and re-reads the server state rather than assuming the next step', async () => {
  authenticatedFetch
    .mockReturnValueOnce(
      json(onboarding('PROFESSIONAL_PROFILE', {
        onboardingState: 'PROFILE_PENDING',
        mobileVerified: true,
      })) as never,
    )
    .mockReturnValueOnce(json({ currentRole: 'Staff Engineer' }) as never)
    .mockReturnValueOnce(
      json(onboarding('LEARNING_JOURNEY', {
        onboardingState: 'JOURNEY_PENDING',
        mobileVerified: true,
      })) as never,
    )
    // The journey step loads the domain catalog once the server places the learner on it.
    .mockReturnValueOnce(json([{ code: 'KAFKA', name: 'Apache Kafka' }]) as never);

  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );

  fireEvent.change(await screen.findByLabelText('Current role'), {
    target: { value: 'Staff Engineer' },
  });
  fireEvent.change(screen.getByLabelText('Years of experience'), {
    target: { value: 'FIVE_TO_TEN_YEARS' },
  });
  fireEvent.change(screen.getByLabelText('Primary expertise'), {
    target: { value: 'Distributed systems' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

  // The server moved the learner to the journey step; the component renders what it was told.
  await screen.findByText('Your first learning goal');
  expect(screen.queryByText('dashboard')).toBeNull();

  const put = authenticatedFetch.mock.calls[1];
  expect(String(put[1])).toContain('/api/v1/me/professional-profile');
  expect((put[2] as RequestInit).method).toBe('PUT');
  // No onboarding state in the payload: the client describes the learner, never their position.
  const body = JSON.parse(String((put[2] as RequestInit).body));
  expect(body).not.toHaveProperty('onboardingState');
  expect(body).not.toHaveProperty('nextStep');
  // An unanswered optional self-rating is absent, not an empty string the server would reject.
  expect(body.declaredSkillLevel).toBeNull();
});

it('resumes at the profile step on a fresh mount, holding no local position', async () => {
  // mockImplementation, not mockReturnValue: a Response body can be consumed once, so a single
  // shared Response would make the second mount fail to parse and mask what this test asserts.
  authenticatedFetch.mockImplementation(
    () =>
      json(onboarding('PROFESSIONAL_PROFILE', {
        onboardingState: 'PROFILE_PENDING',
        mobileVerified: true,
      })) as never,
  );

  const { unmount } = render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  await screen.findByText('Your professional background');
  unmount();

  // Signing out and back in is the same thing as a remount here: the step comes from the server on
  // every mount, so there is no cached wizard position to go stale or be edited.
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  await screen.findByText('Your professional background');
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});

it('never sends a learner identifier; ownership comes from the token', async () => {
  authenticatedFetch
    .mockReturnValueOnce(json(onboarding('MOBILE_VERIFICATION')) as never)
    .mockReturnValueOnce(json({ challengeId: 'chal-1', resendAfter: '2026-01-01T00:00:00Z' }) as never);

  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  fireEvent.click(await screen.findByRole('button', { name: 'Send code' }));

  await waitFor(() => expect(authenticatedFetch).toHaveBeenCalledTimes(2));
  const sendCall = authenticatedFetch.mock.calls[1];
  expect(String(sendCall[1])).toBe('http://localhost:8080/api/v1/me/mobile/send-otp');
  // No learnerId, no subject: a caller that cannot name a victim needs no authorization logic to
  // stop it from naming one.
  expect(sendCall[2]?.body).toBeUndefined();
});

it('surfaces the cooldown refusal without clearing the challenge', async () => {
  authenticatedFetch
    .mockReturnValueOnce(json(onboarding('MOBILE_VERIFICATION')) as never)
    .mockReturnValueOnce(json({ challengeId: 'chal-1', resendAfter: '2026-01-01T00:00:00Z' }) as never)
    .mockReturnValueOnce(
      json(
        {
          code: 'MOBILE_RESEND_COOLDOWN',
          title: 'Registration request rejected',
          detail: 'A verification code was just sent. Wait for the cooldown to elapse before requesting another.',
          interactionId: 'int-3',
        },
        429,
      ) as never,
    );

  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  fireEvent.click(await screen.findByRole('button', { name: 'Send code' }));
  await screen.findByLabelText('Verification code');
  fireEvent.click(screen.getByRole('button', { name: 'Resend code' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('cooldown');
  // The learner may still hold a valid code from the first send, so the input must stay available.
  expect(screen.getByLabelText('Verification code')).toBeInTheDocument();
});

it('reports a rejected code and lets the learner try again', async () => {
  authenticatedFetch
    .mockReturnValueOnce(json(onboarding('MOBILE_VERIFICATION')) as never)
    .mockReturnValueOnce(json({ challengeId: 'chal-1', resendAfter: '2026-01-01T00:00:00Z' }) as never)
    .mockReturnValueOnce(
      json(
        {
          code: 'MOBILE_OTP_INVALID',
          title: 'Registration request rejected',
          detail: 'The verification code is not valid. Request a new code.',
          interactionId: 'int-4',
        },
        409,
      ) as never,
    );

  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  fireEvent.click(await screen.findByRole('button', { name: 'Send code' }));
  fireEvent.change(await screen.findByLabelText('Verification code'), {
    target: { value: '000000' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('not valid');
  expect(screen.getByRole('button', { name: 'Verify' })).toBeEnabled();
});

it('caches no onboarding state in browser storage', async () => {
  authenticatedFetch.mockReturnValue(
    json(onboarding('COMPLETE', { onboardingState: 'ONBOARDED', mobileVerified: true })) as never,
  );
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  await screen.findByText('dashboard');
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});

it('does not render the dashboard for an unknown nextStep', async () => {
  authenticatedFetch.mockReturnValue(
    json({ onboardingState: 'SOMETHING_NEW', nextStep: 'SOMETHING_NEW' }) as never,
  );
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  // The previous default fell through to children, so any state this build did not recognise --
  // including a server ahead of it -- admitted the learner.
  await screen.findByText('Onboarding is not complete');
  expect(screen.queryByText('dashboard')).toBeNull();
});

it('submits the journey and admits the learner once the server reports completion', async () => {
  authenticatedFetch
    .mockReturnValueOnce(
      json(onboarding('LEARNING_JOURNEY', {
        onboardingState: 'JOURNEY_PENDING',
        mobileVerified: true,
      })) as never,
    )
    .mockReturnValueOnce(json([{ code: 'KAFKA', name: 'Apache Kafka' }]) as never)
    .mockReturnValueOnce(json({ id: 'j-1', primaryDomainCode: 'KAFKA' }) as never)
    .mockReturnValueOnce(
      json(onboarding('COMPLETE', {
        onboardingState: 'ONBOARDED',
        mobileVerified: true,
      })) as never,
    );

  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );

  fireEvent.change(await screen.findByLabelText('What are you aiming for?'), {
    target: { value: 'ROLE_TRANSITION' },
  });
  fireEvent.change(screen.getByLabelText('Role you are working towards'), {
    target: { value: 'Principal Engineer' },
  });
  // The catalog is served, not hard-coded: the option exists because the server returned it.
  fireEvent.change(await screen.findByLabelText('What do you want to learn?'), {
    target: { value: 'KAFKA' },
  });
  fireEvent.change(screen.getByLabelText('How far do you want to take it?'), {
    target: { value: '0.700' },
  });
  fireEvent.change(screen.getByLabelText('Pace'), { target: { value: 'STEADY' } });
  fireEvent.change(screen.getByLabelText('Hours per week'), { target: { value: '8' } });
  fireEvent.click(screen.getByRole('button', { name: 'Finish' }));

  // Admitted only because the re-read reported ONBOARDED/COMPLETE, not because the POST returned 200.
  await screen.findByText('dashboard');

  const post = authenticatedFetch.mock.calls[2];
  expect(String(post[1])).toContain('/api/v1/me/learning-journeys');
  expect((post[2] as RequestInit).method).toBe('POST');
  const body = JSON.parse(String((post[2] as RequestInit).body));
  expect(body).not.toHaveProperty('onboardingState');
  expect(body.primaryDomainCode).toBe('KAFKA');
  // An unset optional date is absent rather than an empty string the server would reject.
  expect(body.targetDate).toBeNull();
});

it('surfaces a failure to load the learning domains rather than an empty picker', async () => {
  authenticatedFetch
    .mockReturnValueOnce(
      json(onboarding('LEARNING_JOURNEY', {
        onboardingState: 'JOURNEY_PENDING',
        mobileVerified: true,
      })) as never,
    )
    .mockReturnValueOnce(json({ detail: 'boom' }, 500) as never);

  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );

  // An empty select with no explanation reads as "there is nothing to learn" rather than as a
  // failed request, and the learner has no way to tell the difference.
  await screen.findByRole('alert');
  expect(screen.queryByText('dashboard')).toBeNull();
});

it('keeps the learner on the journey step when the submission fails', async () => {
  authenticatedFetch
    .mockReturnValueOnce(
      json(onboarding('LEARNING_JOURNEY', {
        onboardingState: 'JOURNEY_PENDING',
        mobileVerified: true,
      })) as never,
    )
    .mockReturnValueOnce(json([{ code: 'KAFKA', name: 'Apache Kafka' }]) as never)
    .mockReturnValueOnce(json({ detail: 'projection failed' }, 503) as never);

  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );

  fireEvent.change(await screen.findByLabelText('What are you aiming for?'), {
    target: { value: 'CERTIFICATION' },
  });
  fireEvent.change(screen.getByLabelText('Role you are working towards'), {
    target: { value: 'Platform Engineer' },
  });
  fireEvent.change(await screen.findByLabelText('What do you want to learn?'), {
    target: { value: 'KAFKA' },
  });
  fireEvent.change(screen.getByLabelText('How far do you want to take it?'), {
    target: { value: '1.000' },
  });
  fireEvent.change(screen.getByLabelText('Pace'), { target: { value: 'INTENSIVE' } });
  fireEvent.change(screen.getByLabelText('Hours per week'), { target: { value: '20' } });
  // An optional date, supplied this time: it must be sent as a value rather than dropped.
  fireEvent.change(screen.getByLabelText('Target date (optional)'), {
    target: { value: '2027-06-30' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Finish' }));

  await screen.findByRole('alert');
  // The server rejected it, so the learner is not onboarded and must not see the app.
  expect(screen.queryByText('dashboard')).toBeNull();
  const body = JSON.parse(String((authenticatedFetch.mock.calls[2][2] as RequestInit).body));
  expect(body.targetDate).toBe('2027-06-30');
  expect(body.weeklyHours).toBe(20);
});

it('does not preselect a learning domain', async () => {
  authenticatedFetch
    .mockReturnValueOnce(
      json(onboarding('LEARNING_JOURNEY', {
        onboardingState: 'JOURNEY_PENDING',
        mobileVerified: true,
      })) as never,
    )
    .mockReturnValueOnce(json([{ code: 'KAFKA', name: 'Apache Kafka' }]) as never);

  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );

  // Doc 03: Kafka is never auto-selected. With a single-entry catalog that is only true if nothing
  // preselects it, so the empty value must survive the catalog load.
  const select = (await screen.findByLabelText('What do you want to learn?')) as HTMLSelectElement;
  await waitFor(() => expect(screen.getByRole('option', { name: 'Apache Kafka' })).toBeTruthy());
  expect(select.value).toBe('');
  expect(screen.getByRole('button', { name: 'Finish' })).toBeDisabled();
});

it('does not render the dashboard for JOURNEY_PENDING', async () => {
  authenticatedFetch
    .mockReturnValueOnce(
      json({ onboardingState: 'JOURNEY_PENDING', nextStep: 'LEARNING_JOURNEY' }) as never,
    )
    .mockReturnValueOnce(json([{ code: 'KAFKA', name: 'Apache Kafka' }]) as never);
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  // JOURNEY_PENDING now renders its own step rather than the unrecognised-state message, but the
  // assertion that matters is unchanged: a completed profile is not a completed onboarding.
  await screen.findByText('Your first learning goal');
  expect(screen.queryByText('dashboard')).toBeNull();
});

it('does not render the dashboard when only one half of completion is reported', async () => {
  for (const partial of [
    { onboardingState: 'ONBOARDED', nextStep: 'PROFESSIONAL_PROFILE' },
    { onboardingState: 'PROFILE_PENDING', nextStep: 'COMPLETE' },
  ]) {
    authenticatedFetch.mockReset();
    authenticatedFetch.mockReturnValue(json(partial) as never);
    const view = render(
      <OnboardingResume>
        <p>dashboard</p>
      </OnboardingResume>,
    );
    await waitFor(() => expect(authenticatedFetch).toHaveBeenCalled());
    expect(screen.queryByText('dashboard')).toBeNull();
    view.unmount();
  }
});

it('renders children only for explicit ONBOARDED and COMPLETE', async () => {
  authenticatedFetch.mockReturnValue(
    json({
      onboardingState: 'ONBOARDED',
      nextStep: 'COMPLETE',
      emailVerified: true,
      mobileVerified: true,
    }) as never,
  );
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  expect(await screen.findByText('dashboard')).toBeInTheDocument();
});

it('does not render the dashboard for a malformed response', async () => {
  authenticatedFetch.mockReturnValue(json({}) as never);
  render(
    <OnboardingResume>
      <p>dashboard</p>
    </OnboardingResume>,
  );
  await screen.findByText('Onboarding is not complete');
  expect(screen.queryByText('dashboard')).toBeNull();
});
