import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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

it('rejects mismatched passwords in the browser without calling the registration API', () => {
  render(<RegistrationPage />);
  fillAndSubmit({ 'Confirm password': 'different horse battery' });

  const confirmation = screen.getByLabelText('Confirm password') as HTMLInputElement;
  expect(confirmation.validationMessage).toBe('The password and its confirmation do not match.');
  expect(fetchMock).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: 'Register' })).toBeEnabled();
});

it('sends the server-known consent versions, not only the acceptance booleans', async () => {
  fetchMock.mockReturnValue(accepted());
  render(<RegistrationPage />);
  fillAndSubmit();

  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  const body = JSON.parse(String(fetchMock.mock.calls[0][1].body));
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
  const headers = fetchMock.mock.calls[0][1].headers as Headers;
  expect(headers.get('Idempotency-Key')).toBeTruthy();
  expect(headers.get('X-Interaction-Id')).toBeTruthy();
});

it('never collects a date of birth', () => {
  render(<RegistrationPage />);
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

  expect(keyOf(0)).not.toBe(keyOf(1));
});

it('mints a new key when the registration data materially changes', async () => {
  fetchMock.mockRejectedValueOnce(new Error('connection reset'));
  render(<RegistrationPage />);
  fillAndSubmit();
  await screen.findByRole('alert');

  fetchMock.mockReturnValueOnce(accepted());
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

describe('resending the verification email', () => {
  /** Registers successfully, leaving the "Check your email" panel on screen. */
  async function reachCheckYourEmail() {
    fetchMock.mockReturnValue(accepted());
    render(<RegistrationPage />);
    fillAndSubmit();
    await screen.findByRole('heading', { name: 'Check your email' });
    fetchMock.mockClear();
  }

  it('posts the registered address to the resend route', async () => {
    await reachCheckYourEmail();
    fetchMock.mockReturnValue(
      Promise.resolve(new Response(JSON.stringify({ status: 'EMAIL_VERIFICATION' }), {
        status: 202, headers: { 'Content-Type': 'application/json' },
      })),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Send it again' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toMatch(/\/api\/v1\/registration\/verification\/resend$/);
    expect(init.method).toBe('POST');
    // The form was reset on submit, so the address has to have been captured before that.
    expect(JSON.parse(String(init.body))).toEqual({ email: 'asha@example.com' });
  });

  it('sends no Idempotency-Key, because there is no operation to fork', async () => {
    await reachCheckYourEmail();
    fetchMock.mockReturnValue(
      Promise.resolve(new Response(null, { status: 202 })),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Send it again' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const headers = new Headers(fetchMock.mock.calls[0][1].headers);
    expect(headers.get('Idempotency-Key')).toBeNull();
  });

  it('confirms without revealing whether the address is registered', async () => {
    await reachCheckYourEmail();
    fetchMock.mockReturnValue(Promise.resolve(new Response(null, { status: 202 })));

    fireEvent.click(screen.getByRole('button', { name: 'Send it again' }));

    // "another link is on its way" only if the address is awaiting verification -- never a flat
    // "sent". A definite confirmation here would answer the enumeration question the server's
    // uniform response exists to refuse.
    const confirmation = await screen.findByRole('status');
    expect(confirmation).toHaveTextContent(/If that address is awaiting verification/);
    expect(confirmation.textContent).not.toMatch(/\bsent\b/i);
  });

  it('keeps the instructions on screen when the resend fails', async () => {
    await reachCheckYourEmail();
    fetchMock.mockReturnValue(Promise.resolve(new Response(null, { status: 429 })));

    fireEvent.click(screen.getByRole('button', { name: 'Send it again' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    // The learner is mid-recovery. Replacing the panel with an error would remove the very
    // instructions they came back to act on.
    expect(screen.getByRole('heading', { name: 'Check your email' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send it again' })).toBeEnabled();
  });

  it('survives a transport failure without an unhandled rejection', async () => {
    await reachCheckYourEmail();
    fetchMock.mockRejectedValue(new TypeError('network down'));

    fireEvent.click(screen.getByRole('button', { name: 'Send it again' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
