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
});
