import { describe, expect, it } from 'vitest';

import { normalizeLocalRedirect } from './redirect';

describe('normalizeLocalRedirect', () => {
  it('接受编码或未编码的站内绝对路由', () => {
    expect(normalizeLocalRedirect('%2Fsystem%2Fuser%3Fpage%3D1', '/')).toBe(
      '/system/user?page=1',
    );
    expect(normalizeLocalRedirect('/system/user#profile', '/')).toBe(
      '/system/user#profile',
    );
  });

  it.each([
    'https%3A%2F%2Fevil.example',
    '%2F%2Fevil.example',
    '%2Fsafe%5C..%5Cevil',
    '%E0%A4%A',
    '%2Fsafe%0Ainjected',
  ])('拒绝外部、反斜杠、畸形编码或控制字符目标：%s', (redirect) => {
    expect(normalizeLocalRedirect(redirect, '/dashboard')).toBe('/dashboard');
  });

  it('限制重定向长度并确保 fallback 本身安全', () => {
    expect(
      normalizeLocalRedirect(`/${'a'.repeat(2048)}`, '//evil.example'),
    ).toBe('/');
  });
});
