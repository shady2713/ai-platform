import { describe, expect, it } from 'vitest';

import { resolveNavigationDestination } from './navigation-destination';

describe('navigation destination', () => {
  it.each([
    [
      '/system/user?status=1#list',
      { kind: 'internal', path: '/system/user?status=1#list' },
    ],
    [
      'https://example.com/docs?id=1',
      { kind: 'external', url: 'https://example.com/docs?id=1' },
    ],
  ])('accepts an allowlisted destination: %s', (value, expected) => {
    expect(resolveNavigationDestination(value)).toEqual(expected);
  });

  it.each([
    '',
    ' /system/user',
    'system/user',
    '//example.com/docs',
    String.raw`/\example.com/docs`,
    String.raw`/system\user`,
    'http://example.com/docs',
    'https://operator@example.com/docs',
    'javascript:alert(1)',
    '/system/user\n',
    `/${'a'.repeat(2048)}`,
  ])('rejects a non-allowlisted destination: %s', (value) => {
    expect(resolveNavigationDestination(value)).toBeUndefined();
  });
});
