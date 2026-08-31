import { useRef, useState, type FormEvent } from 'react';
import { beginInteraction, interactionFetch, toApiError } from '../platform/apiClient';

const API = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

const COUNTRIES = [
  ['IN', 'India'],
  ['US', 'United States'],
  ['GB', 'United Kingdom'],
  ['CA', 'Canada'],
  ['AU', 'Australia'],
  ['AE', 'United Arab Emirates'],
  ['SG', 'Singapore'],
  ['DE', 'Germany'],
  ['FR', 'France'],
  ['NL', 'Netherlands'],
  ['IE', 'Ireland'],
  ['CH', 'Switzerland'],
  ['SE', 'Sweden'],
  ['JP', 'Japan'],
  ['KR', 'South Korea'],
  ['NZ', 'New Zealand'],
  ['BR', 'Brazil'],
  ['MX', 'Mexico'],
  ['ZA', 'South Africa'],
] as const;

/**
 * The consent revisions this build was shipped against.
 *
 * <p>Echoed to the server, which compares them with its own and refuses anything it did not issue.
 * The browser therefore cannot invent a version, and a deployment whose documents have moved on
 * rejects a stale build rather than recording an acceptance against the wrong revision. Keeping them
 * here rather than fetching them is a deliberate PR-A limitation: a mismatch fails closed and
 * visibly, and the endpoint that would serve them belongs with the documents themselves.
 */
const CONSENT = {
  termsVersion: 'terms-v1',
  privacyVersion: 'privacy-v1',
  adultStatementVersion: 'adult-18-v1',
} as const;

interface RegistrationAttempt {
  readonly fingerprint: string;
  readonly idempotencyKey: string;
}

/** The values that identify one logical registration. Password is deliberately excluded. */
function fingerprintOf(payload: Record<string, unknown>): string {
  return [
    payload.firstName,
    payload.lastName,
    payload.email,
    payload.mobileNumber,
    payload.country,
    payload.city,
    payload.termsVersion,
    payload.privacyVersion,
    payload.adultStatementVersion,
  ]
    .map((value) => String(value ?? '').trim().toLowerCase())
    .join('\u0000');
}

type Status =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'submitted' }
  | { kind: 'failed'; message: string; supportCode: string };

export function RegistrationPage() {
  const [status, setStatus] = useState<Status>({ kind: 'idle' });
  const attempt = useRef<RegistrationAttempt | null>(null);

  function idempotencyKeyFor(payload: Record<string, unknown>): string {
    const fingerprint = fingerprintOf(payload);
    if (attempt.current?.fingerprint === fingerprint) {
      return attempt.current.idempotencyKey;
    }
    const idempotencyKey = crypto.randomUUID();
    attempt.current = { fingerprint, idempotencyKey };
    return idempotencyKey;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    setStatus({ kind: 'submitting' });

    const payload = {
      firstName: fields.get('firstName'),
      lastName: fields.get('lastName'),
      email: fields.get('email'),
      mobileNumber: fields.get('mobileNumber'),
      country: fields.get('country'),
      city: fields.get('city') || null,
      password: fields.get('password'),
      confirmPassword: fields.get('confirmPassword'),
      ...CONSENT,
      termsAccepted: fields.has('termsAccepted'),
      privacyAccepted: fields.has('privacyAccepted'),
      adultConfirmed: fields.has('adultConfirmed'),
    };

    const interaction = beginInteraction();
    try {
      const response = await interactionFetch(interaction, `${API}/api/v1/registration`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': idempotencyKeyFor(payload),
        },
        body: JSON.stringify(payload),
      });
      if (!response.ok) {
        throw await toApiError(response, interaction.interactionId);
      }
      attempt.current = null;
      form.reset();
      setStatus({ kind: 'submitted' });
    } catch (error) {
      setStatus({
        kind: 'failed',
        message: error instanceof Error ? error.message : 'Registration could not be completed.',
        supportCode:
          error && typeof error === 'object' && 'supportCode' in error
            ? String((error as { supportCode: unknown }).supportCode)
            : interaction.interactionId,
      });
    }
  }

  if (status.kind === 'submitted') {
    return (
      <main className="app">
        <p className="eyebrow">RAMALS professional</p>
        <h1>Check your email</h1>
        <p>
          If that address can be registered, we have asked Keycloak to send a verification link.
          Follow it, then sign in to continue setting up your account.
        </p>
        <p><a href="/">Continue to sign in</a></p>
      </main>
    );
  }

  const busy = status.kind === 'submitting';
  return (
    <main className="app">
      <p className="eyebrow">RAMALS professional</p>
      <h1>Create your learner account</h1>
      <form className="registration-form" onSubmit={submit} noValidate={false}>
        <label>
          First name
          <input name="firstName" required maxLength={100} autoComplete="given-name" />
        </label>
        <label>
          Last name
          <input name="lastName" required maxLength={100} autoComplete="family-name" />
        </label>
        <label>
          Email
          <input name="email" type="email" required maxLength={320} autoComplete="email" />
        </label>
        <label>
          Mobile
          <input name="mobileNumber" type="tel" required maxLength={32} autoComplete="tel" />
        </label>
        <label>
          Country
          <select name="country" required defaultValue="IN" autoComplete="country">
            {COUNTRIES.map(([code, name]) => (
              <option key={code} value={code}>{code} ({name})</option>
            ))}
          </select>
        </label>
        <label>
          City (optional)
          <input name="city" maxLength={120} autoComplete="address-level2" />
        </label>
        <label>
          Password
          <input name="password" type="password" required minLength={12} maxLength={128} autoComplete="new-password" />
        </label>
        <label>
          Confirm password
          <input name="confirmPassword" type="password" required minLength={12} maxLength={128} autoComplete="new-password" />
        </label>
        <label className="check">
          <input name="termsAccepted" type="checkbox" required />I accept the Terms.
        </label>
        <label className="check">
          <input name="privacyAccepted" type="checkbox" required />I accept the Privacy Notice.
        </label>
        <label className="check">
          <input name="adultConfirmed" type="checkbox" required />I confirm that I am 18 years or older.
        </label>
        <button disabled={busy}>{busy ? 'Submitting…' : 'Register'}</button>
        {status.kind === 'failed' && (
          <div role="alert" className="error-banner">
            {status.message}
            <p className="support-code">Support code: {status.supportCode}</p>
          </div>
        )}
      </form>
      <p><a href="/">Already registered? Sign in</a></p>
    </main>
  );
}
