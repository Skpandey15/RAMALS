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

describe('browser storage policy', () => {
  it('never persists bearer tokens or business data in web storage', () => {
    for (const file of sourceFiles(resolve('src'))) {
      const source = readFileSync(file, 'utf8');
      expect(source, file).not.toMatch(/localStorage|sessionStorage|indexedDB/);
    }
  });
});
