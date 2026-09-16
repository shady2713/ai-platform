import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

import { describe, expect, it } from 'vitest';

const requireElementPlus = createRequire(
  createRequire(import.meta.url).resolve('element-plus/package.json'),
);
// Exercise the exact transitive dependency used by the production UI library.
const { default: fromPairs } = await import(
  pathToFileURL(requireElementPlus.resolve('lodash-es/fromPairs.js')).href
);
const { default: template } = await import(
  pathToFileURL(requireElementPlus.resolve('lodash-es/template.js')).href
);

describe('element Plus dependency compatibility', () => {
  it('converts nonempty pairs without an unresolved internal helper', () => {
    expect(fromPairs([['title', 'admin']])).toEqual({ title: 'admin' });
    expect(fromPairs([])).toEqual({});
  });

  it('preserves prototype safety for user-controlled keys', () => {
    const payload = { polluted: true };
    const result = fromPairs([['__proto__', payload]]);

    expect(Object.getPrototypeOf(result)).toBe(Object.prototype);
    expect(Object.hasOwn(result, '__proto__')).toBe(true);
    expect(Object.getOwnPropertyDescriptor(result, '__proto__')?.value).toBe(
      payload,
    );
    expect(Object.hasOwn(Object.prototype, 'polluted')).toBe(false);
  });

  it('compiles a normal template with its actual internal helpers', () => {
    expect(template('Hello <%= user %>')({ user: 'admin' })).toBe(
      'Hello admin',
    );
  });
});
