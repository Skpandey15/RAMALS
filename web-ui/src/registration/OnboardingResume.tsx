import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { authenticatedFetch } from '../auth/authClient';
import { beginInteraction, toApiError } from '../platform/apiClient';

const API = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

interface OnboardingState {
  readonly onboardingState: string;
  readonly nextStep: string;
  readonly emailVerified: boolean;
  readonly mobileVerified: boolean;
}

interface Challenge {
  readonly challengeId: string;
  readonly resendAfter: string;
}

/**
 * Reads the server's view of onboarding.
 *
 * Kept outside the component, and free of any state update, so that both the mount effect and the
 * post-verification refresh share one definition of what "ask the server" means.
 */
async function loadOnboarding(): Promise<OnboardingState> {
  const interaction = beginInteraction();
  const response = await authenticatedFetch(interaction, `${API}/api/v1/me/onboarding`);
  if (!response.ok) {
    throw await toApiError(response, interaction.interactionId);
  }
  return (await response.json()) as OnboardingState;
}

/**
 * Resumes professional onboarding after sign-in, and admits the learner to the app once it is done.
 *
 * <p><strong>The server decides; this renders.</strong> The step shown is whatever
 * {@code GET /api/v1/me/onboarding} reports. Nothing is cached in {@code localStorage} or
 * {@code sessionStorage}: an onboarding state held in the browser would be both a stale copy and one
 * the learner could edit, and the endpoints that matter re-derive the learner's position from the
 * authenticated subject on every call regardless of what this component believes.
 *
 * <p>Interruption is the normal case rather than the exception — the flow deliberately routes the
 * learner out to their email and back — so the component's job is to answer "where was I" on every
 * mount, not to hold a wizard's position.
 */
export function OnboardingResume({ children }: { children: ReactNode }) {
  const [state, setState] = useState<OnboardingState | null>(null);
  const [challenge, setChallenge] = useState<Challenge | null>(null);
  const [otp, setOtp] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setState(await loadOnboarding());
      setLoadFailed(false);
    } catch {
      // Fail closed: without a trusted answer the app is not entered. Rendering the dashboard on a
      // failed lookup would let a transient error do what a forged state is not allowed to do.
      setLoadFailed(true);
    }
  }, []);

  useEffect(() => {
    // The guard is not only about the lint rule: a learner who navigates away mid-request would
    // otherwise have state written into an unmounted tree, and on a slow network that is the common
    // case rather than the rare one.
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await loadOnboarding();
        if (!cancelled) {
          setState(loaded);
          setLoadFailed(false);
        }
      } catch {
        if (!cancelled) {
          setLoadFailed(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  async function call(path: string, init?: RequestInit): Promise<Response> {
    const interaction = beginInteraction();
    const response = await authenticatedFetch(interaction, `${API}${path}`, init);
    if (!response.ok) {
      throw await toApiError(response, interaction.interactionId);
    }
    return response;
  }

  async function sendCode() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const response = await call('/api/v1/me/mobile/send-otp', { method: 'POST' });
      setChallenge((await response.json()) as Challenge);
      setNotice('We sent a code to your registered mobile number.');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'The code could not be sent.');
    } finally {
      setBusy(false);
    }
  }

  async function verifyCode() {
    if (!challenge) {
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await call('/api/v1/me/mobile/verify-otp', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ challengeId: challenge.challengeId, otp }),
      });
      setOtp('');
      setChallenge(null);
      // Re-read rather than assume. The server owns the transition, and trusting the 200 to mean a
      // specific next state would put that decision back in the browser.
      await refresh();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'The code could not be verified.');
    } finally {
      setBusy(false);
    }
  }

  if (loadFailed) {
    return (
      <main className="app">
        <h1>We could not load your account</h1>
        <p role="alert">Reload the page, or sign in again if the problem continues.</p>
      </main>
    );
  }

  if (!state) {
    return (
      <main className="app">
        <p role="status">Loading your onboarding…</p>
      </main>
    );
  }

  if (state.nextStep === 'REGISTRATION') {
    return (
      <main className="app">
        <h1>Finish creating your account</h1>
        <p>
          This account has not completed professional registration. Register to continue.
        </p>
        <p>
          <a href="/register">Go to registration</a>
        </p>
      </main>
    );
  }

  if (state.nextStep === 'EMAIL_VERIFICATION') {
    return (
      <main className="app">
        <h1>Verify your email</h1>
        <p>
          Follow the verification link Keycloak sent you, then sign in again to continue.
        </p>
        <button onClick={() => void refresh()} disabled={busy}>
          I have verified my email
        </button>
      </main>
    );
  }

  if (state.nextStep === 'MOBILE_VERIFICATION') {
    return (
      <main className="app">
        <h1>Verify your mobile</h1>
        <p>We will send a code to the mobile number you registered.</p>
        {!challenge && (
          <button onClick={() => void sendCode()} disabled={busy}>
            {busy ? 'Sending…' : 'Send code'}
          </button>
        )}
        {challenge && (
          <>
            <label>
              Verification code
              <input
                value={otp}
                onChange={(event) => setOtp(event.target.value)}
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
              />
            </label>
            <button onClick={() => void verifyCode()} disabled={busy || otp.length !== 6}>
              {busy ? 'Verifying…' : 'Verify'}
            </button>
            <button onClick={() => void sendCode()} disabled={busy}>
              Resend code
            </button>
          </>
        )}
        {notice && <p role="status">{notice}</p>}
        {error && <p role="alert">{error}</p>}
      </main>
    );
  }

  if (state.nextStep === 'PROFESSIONAL_PROFILE') {
    return (
      <main className="app">
        <h1>Identity verified</h1>
        <p>
          Your email and mobile number are confirmed. Building your professional profile and first
          learning journey is the next step.
        </p>
      </main>
    );
  }

  // Dashboard access is explicit, never a fall-through. The previous default admitted any state the
  // UI did not recognise -- a future step such as JOURNEY_PENDING, a malformed response, or simply a
  // server ahead of this build -- which is the one direction an onboarding gate must not fail.
  if (state.onboardingState === 'ONBOARDED' && state.nextStep === 'COMPLETE') {
    return <>{children}</>;
  }

  return (
    <main className="app">
      <h1>Onboarding is not complete</h1>
      <p role="status">
        There is a step left before your account is ready. Reload the page, or sign in again if this
        persists.
      </p>
    </main>
  );
}
