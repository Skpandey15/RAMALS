import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { RegistrationPage } from './RegistrationPage';

/**
 * The public registration form.
 *
 * <p>What matters here is not that the form posts, but what it does and does not do around posting:
 * it sends server-known consent versions rather than booleans alone, it carries an Idempotency-Key,
 * it writes nothing to browser storage, and it never reveals whether an email was already
 * registered.
 */

const originalFetch = globalThis.fetch;
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  if (!globalThis.crypto?.randomUUID) {
    Object.defineProperty(globalThis, 'crypto', {
      value: { randomUUID: () => '11111111-2222-4333-8444-555555555555' },
      configurable: true,
    });
  }
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

function accepted() {
  return Promise.resolve(
    new Response(JSON.stringify({ operationId: 'op-1', nextStep: 'EMAIL_VERIFICATION' }), {
      status: 202,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

function fillAndSubmit(overrides: Record<string, string> = {}) {
  const values: Record<string, string> = {
    'First name': 'Asha',
    'Last name': 'Iyer',
    Email: 'asha@example.com',
    Mobile: '9876543210',
    'Country code': 'IN',
    Password: 'correct horse battery',
    'Confirm password': 'correct horse battery',
    ...overrides,
  };
  for (const [label, value] of Object.entries(values)) {
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
  }
  fireEvent.click(screen.getByLabelText(/I accept the Terms/));
  fireEvent.click(screen.getByLabelText(/I accept the Privacy Notice/));
  fireEvent.click(screen.getByLabelText(/18 years or older/));
  fireEvent.submit(screen.getByRole('button', { name: 'Register' }).closest('form')!);
}

it('sends the server-known consent versions, not only the acceptance booleans', async () => {
  fetchMock.mockReturnValue(accepted());
  render(<RegistrationPage />);
  fillAndSubmit();

  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  const body = JSON.parse(String(fetchMock.mock.calls[0][1].body));
  // A boolean alone is not evidence of what was accepted; the server rejects a version it did not
  // issue, so the acceptance is always bound to a specific document revision.
  expect(body.termsVersion).toBe('terms-v1');
  expect(body.privacyVersion).toBe('privacy-v1');
  expect(body.adultStatementVersion).toBe('adult-18-v1');
  expect(body.termsAccepted).toBe(true);
  expect(body.adultConfirmed).toBe(true);
});

it('carries an Idempotency-Key so a lost response does not create a second account', async () => {
  fetchMock.mockReturnValue(accepted());
  render(<RegistrationPage />);
  fillAndSubmit();

  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  // interactionFetch normalises init.headers into a Headers instance to add the correlation header,
  // so the assertion has to read it as one rather than as a plain object.
  const headers = fetchMock.mock.calls[0][1].headers as Headers;
  expect(headers.get('Idempotency-Key')).toBeTruthy();
  expect(headers.get('X-Interaction-Id')).toBeTruthy();
});

it('never collects a date of birth', () => {
  render(<RegistrationPage />);
  // Adult status is an attested statement version, not a birth date: the professional product can
  // prove the attestation without holding a date it has no present use for.
  expect(screen.queryByLabelText(/date of birth/i)).toBeNull();
  expect(screen.queryByLabelText(/birth/i)).toBeNull();
});

it('offers no way to request a role', () => {
  render(<RegistrationPage />);
  for (const label of [/role/i, /instructor/i, /admin/i, /content author/i]) {
    expect(screen.queryByLabelText(label)).toBeNull();
  }
});

it('writes nothing to browser storage, before or after submitting', async () => {
  fetchMock.mockReturnValue(accepted());
  render(<RegistrationPage />);
  fillAndSubmit();

  await screen.findByText('Check your email');
  // The password most of all, but also the response: onboarding state cached here would be both a
  // stale copy and one the learner could edit.
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});

it('clears the password from the DOM once the submission succeeds', async () => {
  fetchMock.mockReturnValue(accepted());
  render(<RegistrationPage />);
  fillAndSubmit();

  await screen.findByText('Check your email');
  expect(document.body.innerHTML).not.toContain('correct horse battery');
});

it('confirms without revealing whether the address was already registered', async () => {
  fetchMock.mockReturnValue(accepted());
  render(<RegistrationPage />);
  fillAndSubmit();

  const confirmation = await screen.findByText(/If that address can be registered/);
  // The server answers a duplicate identically; the wording must not undo that by implying the
  // account is new.
  expect(confirmation).toBeInTheDocument();
  expect(document.body.textContent).not.toMatch(/already (registered|exists)/i);
});

it('shows the server detail and a support code when the request is refused', async () => {
  fetchMock.mockReturnValue(
    Promise.resolve(
      new Response(
        JSON.stringify({
          code: 'CONSENT_VERSION_UNKNOWN',
          title: 'Registration request rejected',
          detail: 'The submitted terms, privacy or age statement version is not one this service issued.',
          interactionId: 'int-9',
        }),
        { status: 422, headers: { 'Content-Type': 'application/json' } },
      ),
    ),
  );
  render(<RegistrationPage />);
  fillAndSubmit();

  const alert = await screen.findByRole('alert');
  // The detail is the actionable half of an RFC 7807 problem; the title alone would say only that
  // something was rejected.
  expect(alert).toHaveTextContent('is not one this service issued');
  expect(alert).toHaveTextContent('int-9');
});

it('reports a transport failure without leaving the form stuck', async () => {
  fetchMock.mockRejectedValue(new Error('network down'));
  render(<RegistrationPage />);
  fillAndSubmit();

  await screen.findByRole('alert');
  expect(screen.getByRole('button', { name: 'Register' })).toBeEnabled();
});

function keyOf(call: number): string {
  return (fetchMock.mock.calls[call][1].headers as Headers).get('Idempotency-Key')!;
}

it('reuses the Idempotency-Key when a transport failure is retried', async () => {
  // The bug: a key minted per submission means a retry after a lost response arrives under a new
  // key, so the server starts a second registration instead of replaying the completed one.
  fetchMock.mockRejectedValueOnce(new Error('connection reset'));
  render(<RegistrationPage />);
  fillAndSubmit();
  await screen.findByRole('alert');

  fetchMock.mockReturnValueOnce(accepted());
  fillAndSubmit();
  await screen.findByText('Check your email');

  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(keyOf(0)).toBe(keyOf(1));
});

it('reuses the key across repeated server-side failures', async () => {
  fetchMock.mockReturnValue(
    Promise.resolve(
      new Response(JSON.stringify({ code: 'IDENTITY_PROVIDER_UNAVAILABLE', title: 'x' }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
  render(<RegistrationPage />);
  fillAndSubmit();
  await screen.findByRole('alert');
  fillAndSubmit();
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

  expect(keyOf(0)).toBe(keyOf(1));
});

it('retires the key after success, so an independent registration gets a new one', async () => {
  fetchMock.mockReturnValue(accepted());
  const { unmount } = render(<RegistrationPage />);
  fillAndSubmit();
  await screen.findByText('Check your email');
  unmount();

  render(<RegistrationPage />);
  fillAndSubmit({ Email: 'second@example.com' });
  await screen.findByText('Check your email');

  // Replaying a completed key for a different registration would have it answered as that one.
  expect(keyOf(0)).not.toBe(keyOf(1));
});

it('mints a new key when the registration data materially changes', async () => {
  fetchMock.mockRejectedValueOnce(new Error('connection reset'));
  render(<RegistrationPage />);
  fillAndSubmit();
  await screen.findByRole('alert');

  fetchMock.mockReturnValueOnce(accepted());
  // Corrected email after the failure: the same key against a different body is refused by the
  // server as an idempotency conflict, so the client must not send it.
  fillAndSubmit({ Email: 'corrected@example.com' });
  await screen.findByText('Check your email');

  expect(keyOf(0)).not.toBe(keyOf(1));
});

it('mints a new key when the mobile number changes', async () => {
  fetchMock.mockRejectedValueOnce(new Error('connection reset'));
  render(<RegistrationPage />);
  fillAndSubmit();
  await screen.findByRole('alert');

  fetchMock.mockReturnValueOnce(accepted());
  fillAndSubmit({ Mobile: '9000000001' });
  await screen.findByText('Check your email');

  expect(keyOf(0)).not.toBe(keyOf(1));
});

it('keeps the retry key out of browser storage', async () => {
  fetchMock.mockRejectedValueOnce(new Error('connection reset'));
  render(<RegistrationPage />);
  fillAndSubmit();
  await screen.findByRole('alert');

  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});
