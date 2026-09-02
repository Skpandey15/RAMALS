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

interface ProfileForm {
  currentRole: string;
  experienceBand: string;
  primaryExpertise: string;
  declaredSkillLevel: string;
}

const EMPTY_PROFILE: ProfileForm = {
  currentRole: '',
  experienceBand: '',
  primaryExpertise: '',
  declaredSkillLevel: '',
};

/**
 * The controlled vocabularies the server accepts, with the wording the learner reads.
 *
 * The values are the server's; only the labels are presentational. Sending a label, or inventing a
 * value the API does not know, is a 400 -- so the pairing lives here rather than being assembled at
 * render time where a typo would only surface as a rejected submission.
 */
const EXPERIENCE_BANDS: ReadonlyArray<readonly [string, string]> = [
  ['LESS_THAN_ONE_YEAR', 'Less than a year'],
  ['ONE_TO_THREE_YEARS', '1 to 3 years'],
  ['THREE_TO_FIVE_YEARS', '3 to 5 years'],
  ['FIVE_TO_TEN_YEARS', '5 to 10 years'],
  ['OVER_TEN_YEARS', 'More than 10 years'],
];

const SKILL_LEVELS: ReadonlyArray<readonly [string, string]> = [
  ['BEGINNER', 'Beginner'],
  ['INTERMEDIATE', 'Intermediate'],
  ['ADVANCED', 'Advanced'],
  ['EXPERT', 'Expert'],
];

interface LearningDomain {
  readonly code: string;
  readonly name: string;
}

interface JourneyForm {
  goalType: string;
  targetRole: string;
  learningIntensity: string;
  weeklyHours: string;
  primaryDomainCode: string;
  targetProficiency: string;
  targetDate: string;
}

const EMPTY_JOURNEY: JourneyForm = {
  goalType: '',
  targetRole: '',
  learningIntensity: '',
  weeklyHours: '',
  // No default, deliberately. Doc 03 requires that Kafka is never auto-selected, and preselecting
  // the only catalog entry would choose for the learner rather than let them choose.
  primaryDomainCode: '',
  targetProficiency: '',
  targetDate: '',
};

const GOAL_TYPES: ReadonlyArray<readonly [string, string]> = [
  ['ROLE_TRANSITION', 'Move into a new role'],
  ['DEPTH_IN_CURRENT_ROLE', 'Go deeper in my current role'],
  ['CERTIFICATION', 'Prepare for a certification'],
  ['EXPLORATION', 'Explore something new'],
];

const INTENSITIES: ReadonlyArray<readonly [string, string]> = [
  ['CASUAL', 'Casual'],
  ['STEADY', 'Steady'],
  ['INTENSIVE', 'Intensive'],
];

const PROFICIENCIES: ReadonlyArray<readonly [string, string]> = [
  ['0.500', 'Working knowledge'],
  ['0.700', 'Confident'],
  ['0.850', 'Strong'],
  ['1.000', 'Mastery'],
];

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
  const [profile, setProfile] = useState<ProfileForm>(EMPTY_PROFILE);
  const [journey, setJourney] = useState<JourneyForm>(EMPTY_JOURNEY);
  const [domains, setDomains] = useState<readonly LearningDomain[]>([]);

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

  async function saveProfile() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await call('/api/v1/me/professional-profile', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          currentRole: profile.currentRole.trim(),
          experienceBand: profile.experienceBand,
          primaryExpertise: profile.primaryExpertise.trim(),
          // Optional and non-authoritative: an unanswered self-rating is sent as absent rather than
          // as an empty string, which the server would reject as an unknown level.
          declaredSkillLevel: profile.declaredSkillLevel || null,
        }),
      });
      // Re-read rather than assume, exactly as the mobile step does. A 200 means the profile was
      // stored; only the server can say which step that leaves the learner on.
      await refresh();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Your profile could not be saved.');
    } finally {
      setBusy(false);
    }
  }

  // Loaded only once the server has actually placed the learner on the journey step, so the catalog
  // is not fetched for learners who will never see it.
  useEffect(() => {
    if (state?.nextStep !== 'LEARNING_JOURNEY') {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const response = await call('/api/v1/me/learning-domains');
        const loaded = (await response.json()) as LearningDomain[];
        if (!cancelled) {
          setDomains(loaded);
        }
      } catch {
        if (!cancelled) {
          setError('The list of learning domains could not be loaded.');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [state?.nextStep]);

  async function saveJourney() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await call('/api/v1/me/learning-journeys', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          goalType: journey.goalType,
          targetRole: journey.targetRole.trim(),
          learningIntensity: journey.learningIntensity,
          weeklyHours: Number(journey.weeklyHours),
          primaryDomainCode: journey.primaryDomainCode,
          targetProficiency: journey.targetProficiency,
          // An unset date is absent rather than an empty string, which the server would reject.
          targetDate: journey.targetDate || null,
        }),
      });
      // Re-read rather than assume. Onboarding completes only if the server both stored the journey
      // and projected the goal; a 200 here is not the same claim as "you are onboarded".
      await refresh();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Your learning goal could not be saved.');
    } finally {
      setBusy(false);
    }
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
    const complete =
      profile.currentRole.trim() !== '' &&
      profile.experienceBand !== '' &&
      profile.primaryExpertise.trim() !== '';
    return (
      <main className="app">
        <h1>Your professional background</h1>
        <p>This shapes the learning we build for you. You can change it later.</p>
        <label htmlFor="currentRole">Current role</label>
        <input
          id="currentRole"
          value={profile.currentRole}
          maxLength={120}
          onChange={(event) => setProfile({ ...profile, currentRole: event.target.value })}
        />
        <label htmlFor="experienceBand">Years of experience</label>
        <select
          id="experienceBand"
          value={profile.experienceBand}
          onChange={(event) => setProfile({ ...profile, experienceBand: event.target.value })}
        >
          <option value="">Select</option>
          {EXPERIENCE_BANDS.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <label htmlFor="primaryExpertise">Primary expertise</label>
        <input
          id="primaryExpertise"
          value={profile.primaryExpertise}
          maxLength={120}
          onChange={(event) => setProfile({ ...profile, primaryExpertise: event.target.value })}
        />
        <label htmlFor="declaredSkillLevel">How would you rate yourself? (optional)</label>
        <select
          id="declaredSkillLevel"
          value={profile.declaredSkillLevel}
          onChange={(event) => setProfile({ ...profile, declaredSkillLevel: event.target.value })}
        >
          <option value="">Prefer not to say</option>
          {SKILL_LEVELS.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        {/* Disabled only as a courtesy. The server validates every field and owns the transition, so
            a learner who re-enables this button gets a 400, not a skipped gate. */}
        <button type="button" onClick={() => void saveProfile()} disabled={busy || !complete}>
          Continue
        </button>
        {notice && <p role="status">{notice}</p>}
        {error && <p role="alert">{error}</p>}
      </main>
    );
  }

  if (state.nextStep === 'LEARNING_JOURNEY') {
    const ready =
      journey.goalType !== '' &&
      journey.targetRole.trim() !== '' &&
      journey.learningIntensity !== '' &&
      journey.weeklyHours !== '' &&
      journey.primaryDomainCode !== '' &&
      journey.targetProficiency !== '';
    return (
      <main className="app">
        <h1>Your first learning goal</h1>
        <p>This is the last step. You can change any of it later.</p>
        <label htmlFor="goalType">What are you aiming for?</label>
        <select
          id="goalType"
          value={journey.goalType}
          onChange={(event) => setJourney({ ...journey, goalType: event.target.value })}
        >
          <option value="">Select</option>
          {GOAL_TYPES.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <label htmlFor="targetRole">Role you are working towards</label>
        <input
          id="targetRole"
          value={journey.targetRole}
          maxLength={120}
          onChange={(event) => setJourney({ ...journey, targetRole: event.target.value })}
        />
        <label htmlFor="primaryDomainCode">What do you want to learn?</label>
        <select
          id="primaryDomainCode"
          value={journey.primaryDomainCode}
          onChange={(event) => setJourney({ ...journey, primaryDomainCode: event.target.value })}
        >
          <option value="">Select</option>
          {domains.map((domain) => (
            <option key={domain.code} value={domain.code}>
              {domain.name}
            </option>
          ))}
        </select>
        <label htmlFor="targetProficiency">How far do you want to take it?</label>
        <select
          id="targetProficiency"
          value={journey.targetProficiency}
          onChange={(event) => setJourney({ ...journey, targetProficiency: event.target.value })}
        >
          <option value="">Select</option>
          {PROFICIENCIES.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <label htmlFor="learningIntensity">Pace</label>
        <select
          id="learningIntensity"
          value={journey.learningIntensity}
          onChange={(event) => setJourney({ ...journey, learningIntensity: event.target.value })}
        >
          <option value="">Select</option>
          {INTENSITIES.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <label htmlFor="weeklyHours">Hours per week</label>
        <input
          id="weeklyHours"
          type="number"
          min={1}
          max={40}
          value={journey.weeklyHours}
          onChange={(event) => setJourney({ ...journey, weeklyHours: event.target.value })}
        />
        <label htmlFor="targetDate">Target date (optional)</label>
        <input
          id="targetDate"
          type="date"
          value={journey.targetDate}
          onChange={(event) => setJourney({ ...journey, targetDate: event.target.value })}
        />
        <button type="button" onClick={() => void saveJourney()} disabled={busy || !ready}>
          Finish
        </button>
        {notice && <p role="status">{notice}</p>}
        {error && <p role="alert">{error}</p>}
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
