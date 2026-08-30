import { useState, type FormEvent } from 'react';
import { beginInteraction, interactionFetch, toApiError } from '../platform/apiClient';

const API = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

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

type Status =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'submitted' }
  | { kind: 'failed'; message: string; supportCode: string };

/**
 * Public professional registration.
 *
 * <p>Hosted Keycloak remains the sign-in UI; this form only creates the account. Nothing is written
 * to browser storage — not the password, not the response, not any onboarding state — because the
 * server is the only authority for what a learner has completed, and a value cached here would be
 * both a stale copy and a tamperable one.
 */
export function RegistrationPage() {
  const [status, setStatus] = useState<Status>({ kind: 'idle' });

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    setStatus({ kind: 'submitting' });

    const interaction = beginInteraction();
    try {
      const response = await interactionFetch(interaction, `${API}/api/v1/registration`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          // A stable key per submission, so a retry of a request whose response was lost resolves to
          // the same operation instead of creating a second one.
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({
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
        }),
      });
      if (!response.ok) {
        throw await toApiError(response, interaction.interactionId);
      }
      // Reset before rendering the confirmation, so the password does not remain in the DOM.
      form.reset();
      setStatus({ kind: 'submitted' });
    } catch (error) {
      setStatus({
        kind: 'failed',
        message:
          error instanceof Error ? error.message : 'Registration could not be completed.',
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
        <p>
          <a href="/">Continue to sign in</a>
        </p>
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
          Country code
          <input
            name="country"
            required
            minLength={2}
            maxLength={2}
            pattern="[A-Za-z]{2}"
            placeholder="IN"
            autoComplete="country"
          />
        </label>
        <label>
          City (optional)
          <input name="city" maxLength={120} autoComplete="address-level2" />
        </label>
        <label>
          Password
          <input
            name="password"
            type="password"
            required
            minLength={12}
            maxLength={128}
            autoComplete="new-password"
          />
        </label>
        <label>
          Confirm password
          <input
            name="confirmPassword"
            type="password"
            required
            minLength={12}
            maxLength={128}
            autoComplete="new-password"
          />
        </label>
        <label className="check">
          <input name="termsAccepted" type="checkbox" required />I accept the Terms.
        </label>
        <label className="check">
          <input name="privacyAccepted" type="checkbox" required />I accept the Privacy Notice.
        </label>
        <label className="check">
          <input name="adultConfirmed" type="checkbox" required />I confirm that I am 18 years or
          older.
        </label>
        <button disabled={busy}>{busy ? 'Submitting…' : 'Register'}</button>
        {status.kind === 'failed' && (
          <div role="alert" className="error-banner">
            {status.message}
            <p className="support-code">Support code: {status.supportCode}</p>
          </div>
        )}
      </form>
      <p>
        <a href="/">Already registered? Sign in</a>
      </p>
    </main>
  );
}
