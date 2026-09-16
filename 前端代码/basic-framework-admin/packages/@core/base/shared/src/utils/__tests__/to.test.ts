import { describe, expect, it } from 'vitest';

import { to } from '../to';

describe('to', () => {
  it('resolves to [null, data]', async () => {
    const [err, data] = await to(Promise.resolve(42));
    expect(err).toBeNull();
    expect(data).toBe(42);
  });

  it('rejects to [error, undefined]', async () => {
    const [err, data] = await to(Promise.reject(new Error('boom')));
    expect(err).toBeInstanceOf(Error);
    expect(data).toBeUndefined();
  });

  it('merges errorExt into error', async () => {
    const [err] = await to(Promise.reject(new Error('boom')), { code: 500 });
    expect(err).toMatchObject({ code: 500 });
  });
});
