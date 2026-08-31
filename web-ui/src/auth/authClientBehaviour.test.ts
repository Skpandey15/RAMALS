import { beforeEach, describe, expect, it, vi } from 'vitest';

// A stand-in for the keycloak-js adapter instance. The real module is replaced so the token
// lifecycle can be driven deterministically; the existing authClient.test.ts covers the static
// policy (no web storage, PKCE S256) by scanning the source.
// vi.mock factories are hoisted above module-level consts, so the adapter stub must be created
// inside vi.hoisted to exist by the time authClient constructs it.
const keycloak = vi.hoisted(() => ({
  authenticated: false as boolean | undefined,
  token: undefined as string | undefined,
  init: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  updateToken: vi.fn(),
  clearToken: vi.fn(),
}));

// authClient does `new Keycloak(config)`, so the mock must be constructible.
vi.mock('keycloak-js', () => ({
  default: class {
    constructor() {
      return keycloak;
    }
  },
}));

const interactionFetch = vi.fn();
vi.mock('../platform/apiClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../platform/apiClient')>()),
  interactionFetch: (...args: unknown[]) => interactionFetch(...args),
}));

import { authenticatedFetch, initializeAuthentication, isAuthenticated, login, logout } from './authClient';

const interaction = { interactionId: 'int-1' };

describe('authClient token handling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    keycloak.authenticated = false;
    keycloak.token = undefined;
    keycloak.updateToken.mockResolvedValue(false);
    interactionFetch.mockResolvedValue(new Response());
  });

  it('refuses to call the API when the learner is not authenticated', async () => {
    await expect(authenticatedFetch(interaction, '/api/v1/me')).rejects.toThrow(
      /authentication is required/i,
    );
    expect(interactionFetch).not.toHaveBeenCalled();
  });

  it('revalidates the Keycloak session before each call and sends the bearer credential', async () => {
    keycloak.authenticated = true;
    keycloak.token = 'access-token-value';

    await authenticatedFetch(interaction, '/api/v1/me');

    expect(keycloak.updateToken).toHaveBeenCalledWith(-1);
    const [, , init] = interactionFetch.mock.calls[0] as [unknown, unknown, RequestInit];
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer access-token-value');
  });

  it('fails closed when the adapter yields no token after revalidation', async () => {
    keycloak.authenticated = true;
    keycloak.token = undefined;

    await expect(authenticatedFetch(interaction, '/api/v1/me')).rejects.toThrow(
      /no access token/i,
    );
    expect(keycloak.clearToken).toHaveBeenCalled();
    expect(interactionFetch).not.toHaveBeenCalled();
  });

  it('clears local authentication state when Keycloak session revalidation fails', async () => {
    keycloak.authenticated = true;
    keycloak.token = 'stale-token';
    keycloak.updateToken.mockRejectedValueOnce(new Error('remote session ended'));

    await expect(authenticatedFetch(interaction, '/api/v1/me')).rejects.toThrow(
      /session is no longer valid/i,
    );
    expect(keycloak.clearToken).toHaveBeenCalled();
    expect(interactionFetch).not.toHaveBeenCalled();
  });

  it('clears local authentication state when the resource server returns 401', async () => {
    keycloak.authenticated = true;
    keycloak.token = 'token';
    interactionFetch.mockResolvedValueOnce(new Response(null, { status: 401 }));

    const response = await authenticatedFetch(interaction, '/api/v1/me');

    expect(response.status).toBe(401);
    expect(keycloak.clearToken).toHaveBeenCalled();
  });

  it('preserves caller headers while adding the credential', async () => {
    keycloak.authenticated = true;
    keycloak.token = 'token';

    await authenticatedFetch(interaction, '/api/v1/x', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'key-1' },
    });

    const [, , init] = interactionFetch.mock.calls[0] as [unknown, unknown, RequestInit];
    const headers = new Headers(init.headers);
    expect(headers.get('Idempotency-Key')).toBe('key-1');
    expect(headers.get('Authorization')).toBe('Bearer token');
    expect(init.method).toBe('POST');
  });

  it('reports authentication state and delegates login/logout to the adapter', async () => {
    expect(isAuthenticated()).toBe(false);
    keycloak.authenticated = true;
    expect(isAuthenticated()).toBe(true);

    await login();
    expect(keycloak.login).toHaveBeenCalled();

    await logout();
    // Logout returns the learner to the application origin, never to an attacker-supplied URL.
    expect(keycloak.logout).toHaveBeenCalledWith({ redirectUri: window.location.origin });
  });

  it('recovers a returning session without browser-blocked background iframes', async () => {
    keycloak.init.mockResolvedValue(true);

    await initializeAuthentication();

    expect(keycloak.init).toHaveBeenCalledWith(
      expect.objectContaining({
        flow: 'standard',
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        checkLoginIframe: false,
      }),
    );
  });
});
