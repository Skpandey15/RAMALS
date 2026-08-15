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
    silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    checkLoginIframe: true,
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
