import { describe, expect, it } from 'vitest';

describe('design tokens entry', () => {
  it('loads the design-tokens module (css wiring) without throwing', async () => {
    // The design package is a pure stylesheet entry: importing it must load
    // the token css files. The module itself exports nothing.
    const tokens = await import('../design-tokens');
    expect(Object.keys(tokens)).toEqual([]);

    const entry = await import('../index');
    expect(Object.keys(entry)).toEqual([]);
  });
});
