import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function sourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const full = join(directory, name);
    if (statSync(full).isDirectory()) {
      return sourceFiles(full);
    }
    if (/\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name)) {
      return [full];
    }
    return [];
  });
}

/**
 * Returns source with comments removed.
 *
 * The policy is about executable use of web storage, not about the words. Scanning raw text cannot
 * tell a call that persists a token from a comment explaining that nothing is persisted — so a
 * component that documents the invariant fails the test that protects it, which is a standing
 * incentive to leave the decision unwritten. Stripping comments first makes the assertion mean what
 * it always intended.
 */
function executableCode(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^\s*\/\/.*$/gm, ' ');
}

describe('browser storage policy', () => {
  it('never persists bearer tokens or business data in web storage', () => {
    for (const file of sourceFiles(resolve('src'))) {
      const source = executableCode(readFileSync(file, 'utf8'));
      expect(source, file).not.toMatch(/localStorage|sessionStorage|indexedDB/);
    }
  });
});
