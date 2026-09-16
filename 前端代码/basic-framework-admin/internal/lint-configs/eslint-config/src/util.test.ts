import { describe, expect, it } from 'vitest';

import { interopDefault, toEslintPlugin } from './util';

describe('interopDefault', () => {
  it('returns a module default export', async () => {
    await expect(
      interopDefault(Promise.resolve({ default: 'value' })),
    ).resolves.toBe('value');
  });

  it('returns a module without a default export unchanged', async () => {
    const module = { named: 'value' };

    await expect(interopDefault(module)).resolves.toBe(module);
  });
});

describe('toEslintPlugin', () => {
  it('accepts an object plugin boundary', () => {
    const plugin = { rules: {} };

    expect(toEslintPlugin(plugin)).toBe(plugin);
  });

  it('rejects invalid plugin values', () => {
    expect(() => toEslintPlugin(undefined)).toThrow(
      'ESLint plugin must be an object',
    );
  });
});
