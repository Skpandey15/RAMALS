import Keycloak, { type KeycloakConfig } from 'keycloak-js';
import { interactionFetch, type Interaction } from '../platform/apiClient';

const config: KeycloakConfig = {
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://keycloak.localhost:8080',
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'ramals',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'ramals-web-ui',
};

// Tokens remain only on this adapter instance. Never persist, place in URLs, or log them.
const keycloak = new Keycloak(config);

export async function initializeAuthentication(): Promise<boolean> {
  return keycloak.init({
    flow: 'standard',
    onLoad: 'check-sso',
    pkceMethod: 'S256',
    // Use the top-level check-sso redirect instead of the hidden silent-SSO and session iframes.
    // Browsers can block or indefinitely defer those third-party-cookie checks, leaving the React
    // root empty on a later visit until the entire browser process is restarted.
    // Protected API requests explicitly revalidate the Keycloak session below, so disabling the
    // iframe does not make the in-memory authenticated flag authoritative after remote logout.
    checkLoginIframe: false,
  });
}

export async function login(): Promise<void> {
  await keycloak.login();
}

export async function logout(): Promise<void> {
  await keycloak.logout({ redirectUri: window.location.origin });
}

export function isAuthenticated(): boolean {
  return Boolean(keycloak.authenticated);
}

/**
 * Realm-role authorization helper for coarse UI routing only.
 * Backend authorization remains authoritative for every protected API.
 */
export function hasRealmRole(role: string): boolean {
  return Boolean(keycloak.authenticated && keycloak.hasRealmRole(role));
}

/**
 * Revalidate the server-side Keycloak session before every protected API request.
 *
 * checkLoginIframe is intentionally disabled because browser privacy controls can stall its
 * hidden third-party-cookie checks. Passing -1 forces updateToken to contact Keycloak even when
 * the current access token still has time remaining. If the SSO session was terminated in
 * another tab/device, refresh fails and the adapter's local authentication state is cleared.
 */
async function revalidateSession(): Promise<void> {
  if (!keycloak.authenticated) {
    throw new Error('Authentication is required.');
  }

  try {
    await keycloak.updateToken(-1);
  } catch {
    keycloak.clearToken();
    throw new Error('Authentication session is no longer valid.');
  }

  if (!keycloak.token) {
    keycloak.clearToken();
    throw new Error('No access token is available.');
  }
}

export async function authenticatedFetch(
  interaction: Interaction,
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  await revalidateSession();

  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${keycloak.token}`);

  const response = await interactionFetch(interaction, input, { ...init, headers });
  if (response.status === 401) {
    // The resource server is authoritative. Fail closed if it rejects the token/session so this
    // tab cannot continue to present stale authenticated UI after revocation or remote logout.
    keycloak.clearToken();
  }
  return response;
}
