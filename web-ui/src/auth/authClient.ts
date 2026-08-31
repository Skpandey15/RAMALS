import Keycloak, { type KeycloakConfig } from 'keycloak-js';
import { interactionFetch, type Interaction } from '../platform/apiClient';

const config: KeycloakConfig = {
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8081',
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

export async function authenticatedFetch(
  interaction: Interaction,
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  if (!keycloak.authenticated) {
    throw new Error('Authentication is required.');
  }
  await keycloak.updateToken(30);
  if (!keycloak.token) {
    throw new Error('No access token is available.');
  }
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${keycloak.token}`);
  return interactionFetch(interaction, input, { ...init, headers });
}
