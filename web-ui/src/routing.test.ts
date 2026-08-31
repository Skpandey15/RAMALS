import { describe, expect, it } from 'vitest';
import { isRegistrationPath } from './routing';

describe('isRegistrationPath', () => {
  it.each(['/register', '/register/', '/register///'])('accepts %s', (path) => {
    expect(isRegistrationPath(path)).toBe(true);
  });

  it.each(['/', '/registration', '/register/profile'])('rejects %s', (path) => {
    expect(isRegistrationPath(path)).toBe(false);
  });
});
