import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('OIDC token storage policy', () => {
  it('does not use browser persistence APIs', () => {
    const source = readFileSync(resolve('src/auth/authClient.ts'), 'utf8');
    expect(source).not.toMatch(/localStorage|sessionStorage|indexedDB/i);
  });

  it('pins authorization code flow with S256 PKCE', () => {
    const source = readFileSync(resolve('src/auth/authClient.ts'), 'utf8');
    expect(source).toContain("flow: 'standard'");
    expect(source).toContain("pkceMethod: 'S256'");
  });

  it('uses the shared k3d Keycloak ingress as the local OIDC fallback', () => {
    const source = readFileSync(resolve('src/auth/authClient.ts'), 'utf8');
    expect(source).toContain("VITE_KEYCLOAK_URL ?? 'http://keycloak.localhost:8080'");
    expect(source).not.toContain('http://localhost:8081');
  });

  it('revalidates the Keycloak session before protected API requests when the login iframe is disabled', () => {
    const source = readFileSync(resolve('src/auth/authClient.ts'), 'utf8');
    expect(source).toContain('checkLoginIframe: false');
    expect(source).toContain('await keycloak.updateToken(-1)');
    expect(source).toContain('keycloak.clearToken()');
  });

  it('fails closed when the resource server rejects an authenticated request', () => {
    const source = readFileSync(resolve('src/auth/authClient.ts'), 'utf8');
    expect(source).toContain('response.status === 401');
    expect(source).toContain('keycloak.clearToken()');
  });
});
