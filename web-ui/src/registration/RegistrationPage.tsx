import { useRef, useState, type FormEvent } from 'react';
import { beginInteraction, interactionFetch, toApiError } from '../platform/apiClient';

const API = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

const COUNTRIES = [
  ['IN', 'India'], ['US', 'United States'], ['GB', 'United Kingdom'], ['CA', 'Canada'],
  ['AU', 'Australia'], ['AE', 'United Arab Emirates'], ['SG', 'Singapore'], ['DE', 'Germany'],
  ['FR', 'France'], ['NL', 'Netherlands'], ['IE', 'Ireland'], ['CH', 'Switzerland'],
  ['SE', 'Sweden'], ['JP', 'Japan'], ['KR', 'South Korea'], ['NZ', 'New Zealand'],
  ['BR', 'Brazil'], ['MX', 'Mexico'], ['ZA', 'South Africa'],
] as const;

const CONSENT = {
  termsVersion: 'terms-v1', privacyVersion: 'privacy-v1', adultStatementVersion: 'adult-18-v1',
} as const;

interface RegistrationAttempt { readonly fingerprint: string; readonly idempotencyKey: string; }

function fingerprintOf(payload: Record<string, unknown>): string {
  return [payload.firstName, payload.lastName, payload.email, payload.mobileNumber, payload.country,
    payload.city, payload.termsVersion, payload.privacyVersion, payload.adultStatementVersion]
    .map((value) => String(value ?? '').trim().toLowerCase()).join('\u0000');
}

type Status = { kind: 'idle' } | { kind: 'submitting' } | { kind: 'submitted'; email: string } |
  { kind: 'failed'; message: string; supportCode: string };

/**
 * The resend affordance's own state, kept apart from Status.
 *
 * A failed resend must not tear down the "check your email" panel: the learner is mid-recovery, and
 * replacing the instructions with an error would take away the very thing they came back for.
 */
type ResendState = { kind: 'idle' } | { kind: 'sending' } | { kind: 'done' } | { kind: 'failed' };

export function RegistrationPage() {
  const [status, setStatus] = useState<Status>({ kind: 'idle' });
  const [resendState, setResendState] = useState<ResendState>({ kind: 'idle' });
  const attempt = useRef<RegistrationAttempt | null>(null);

  /**
   * Asks for another verification email.
   *
   * <p>No Idempotency-Key: the route creates nothing a replay could fork, and repetition is bounded
   * server-side per address. Deliberately reports success for any accepted response, because the
   * server answers identically whether or not the address exists and the UI has nothing finer to
   * report even if it wanted to.
   */
  async function resend(email: string) {
    setResendState({ kind: 'sending' });
    const interaction = beginInteraction();
    try {
      const response = await interactionFetch(
        interaction, `${API}/api/v1/registration/verification/resend`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email }) });
      setResendState(response.ok ? { kind: 'done' } : { kind: 'failed' });
    } catch {
      setResendState({ kind: 'failed' });
    }
  }

  function idempotencyKeyFor(payload: Record<string, unknown>): string {
    const fingerprint = fingerprintOf(payload);
    if (attempt.current?.fingerprint === fingerprint) return attempt.current.idempotencyKey;
    const idempotencyKey = crypto.randomUUID();
    attempt.current = { fingerprint, idempotencyKey };
    return idempotencyKey;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    const password = String(fields.get('password') ?? '');
    const confirmPassword = String(fields.get('confirmPassword') ?? '');
    const confirmInput = form.elements.namedItem('confirmPassword') as HTMLInputElement | null;

    if (password !== confirmPassword) {
      confirmInput?.setCustomValidity('The password and its confirmation do not match.');
      confirmInput?.reportValidity();
      setStatus({ kind: 'idle' });
      return;
    }
    confirmInput?.setCustomValidity('');

    setStatus({ kind: 'submitting' });
    const payload = {
      firstName: fields.get('firstName'), lastName: fields.get('lastName'), email: fields.get('email'),
      mobileNumber: fields.get('mobileNumber'), country: fields.get('country'), city: fields.get('city') || null,
      password, confirmPassword, ...CONSENT,
      termsAccepted: fields.has('termsAccepted'), privacyAccepted: fields.has('privacyAccepted'),
      adultConfirmed: fields.has('adultConfirmed'),
    };
    const interaction = beginInteraction();
    try {
      const response = await interactionFetch(interaction, `${API}/api/v1/registration`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKeyFor(payload) },
        body: JSON.stringify(payload),
      });
      if (!response.ok) throw await toApiError(response, interaction.interactionId);
      // Captured before reset(): the resend route needs the address, and the form no longer has it.
      const email = String(fields.get('email') ?? '').trim();
      attempt.current = null;
      form.reset();
      setStatus({ kind: 'submitted', email });
    } catch (error) {
      setStatus({ kind: 'failed',
        message: error instanceof Error ? error.message : 'Registration could not be completed.',
        supportCode: error && typeof error === 'object' && 'supportCode' in error
          ? String((error as { supportCode: unknown }).supportCode) : interaction.interactionId });
    }
  }

  if (status.kind === 'submitted') return (
    <main className="app"><p className="eyebrow">RAMALS professional</p><h1>Check your email</h1>
      <p>If that address can be registered, we have asked Keycloak to send a verification link. Follow it, then sign in to continue setting up your account.</p>
      <p>
        Nothing arrived?{' '}
        <button type="button" className="link-button" onClick={() => void resend(status.email)}
          disabled={resendState.kind === 'sending'}>
          {resendState.kind === 'sending' ? 'Sending…' : 'Send it again'}
        </button>
      </p>
      {resendState.kind === 'done' && (
        // Worded exactly like the panel above it. Confirming that a mail *was* sent would answer
        // "is this address registered?" for anyone who asked, which is what the endpoint's own
        // uniform response is built to prevent -- the UI must not undo it.
        <p role="status">If that address is awaiting verification, another link is on its way.</p>
      )}
      {resendState.kind === 'failed' && (
        <p role="alert">We could not request another email just now. Please try again shortly.</p>
      )}
      <p><a href="/">Continue to sign in</a></p></main>
  );

  const busy = status.kind === 'submitting';
  return (
    <main className="app">
      <p className="eyebrow">RAMALS professional</p><h1>Create your learner account</h1>
      <form className="registration-form" onSubmit={submit} noValidate={false}>
        <label>First name<input name="firstName" required maxLength={100} autoComplete="given-name" /></label>
        <label>Last name<input name="lastName" required maxLength={100} autoComplete="family-name" /></label>
        <label>Email<input name="email" type="email" required maxLength={320} autoComplete="email" /></label>
        <label>Mobile<input name="mobileNumber" type="tel" required maxLength={32} autoComplete="tel" /></label>
        <label>Country code
          <select name="country" required defaultValue="IN" autoComplete="country">
            {COUNTRIES.map(([code, name]) => <option key={code} value={code}>{code} ({name})</option>)}
          </select>
        </label>
        <label>City (optional)<input name="city" maxLength={120} autoComplete="address-level2" /></label>
        <label>Password<input name="password" type="password" required minLength={12} maxLength={128} autoComplete="new-password" /></label>
        <label>Confirm password<input name="confirmPassword" type="password" required minLength={12} maxLength={128} autoComplete="new-password" onInput={(event) => event.currentTarget.setCustomValidity('')} /></label>
        <label className="check"><input name="termsAccepted" type="checkbox" required />I accept the Terms.</label>
        <label className="check"><input name="privacyAccepted" type="checkbox" required />I accept the Privacy Notice.</label>
        <label className="check"><input name="adultConfirmed" type="checkbox" required />I confirm that I am 18 years or older.</label>
        <button disabled={busy}>{busy ? 'Submitting…' : 'Register'}</button>
        {status.kind === 'failed' && <div role="alert" className="error-banner">{status.message}<p className="support-code">Support code: {status.supportCode}</p></div>}
      </form>
      <p><a href="/">Already registered? Sign in</a></p>
    </main>
  );
}
