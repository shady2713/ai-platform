import { describe, expect, it } from 'vitest';

import {
  AI_APPLICATION_PERMISSIONS,
  AI_GRANT_PERMISSIONS,
  EXACT_ORIGIN_PATTERN,
  parseOrigins,
  SECRET_ONCE_WARNING,
  useFormSchema,
  useGridColumns,
} from './data';

/** A09 应用页面数据契约：权限码与迁移种子一致、Origin 只接受精确来源、秘密只提示一次。 */
describe('ai application page data', () => {
  it('declares permission codes matching migration seeds', () => {
    expect(AI_APPLICATION_PERMISSIONS).toEqual({
      create: 'ai:application:create',
      delete: 'ai:application:delete',
      query: 'ai:application:query',
      revoke: 'ai:application:revoke',
      rotate: 'ai:application:rotate',
      update: 'ai:application:update',
    });
    expect(AI_GRANT_PERMISSIONS).toEqual({
      create: 'ai:grant:create',
      query: 'ai:grant:query',
      revoke: 'ai:grant:revoke',
      update: 'ai:grant:update',
    });
  });

  it('accepts only exact https/http origins like the backend', () => {
    expect(EXACT_ORIGIN_PATTERN.test('https://crm.example.com')).toBe(true);
    expect(EXACT_ORIGIN_PATTERN.test('http://localhost:8080')).toBe(true);
    expect(EXACT_ORIGIN_PATTERN.test('https://crm.example.com/path')).toBe(
      false,
    );
    expect(EXACT_ORIGIN_PATTERN.test('https://*.example.com')).toBe(false);
    expect(EXACT_ORIGIN_PATTERN.test('crm.example.com')).toBe(false);
  });

  it('parses multi-line origins and drops blanks', () => {
    expect(
      parseOrigins('https://a.example.com\n\n  https://b.example.com  \n'),
    ).toEqual(['https://a.example.com', 'https://b.example.com']);
  });

  it('keeps form free of prefilled credential and hides it in the list', () => {
    const schema = useFormSchema();
    const credentialFields = schema.filter(
      (item) => item.fieldName === 'credential',
    );
    expect(credentialFields).toHaveLength(1);
    expect(credentialFields[0]?.component).toBe('InputPassword');
    const columns = useGridColumns() ?? [];
    expect(columns.map((column) => column.field)).toContain(
      'credentialConfigured',
    );
    expect(columns.map((column) => column.field)).not.toContain('credential');
    expect(SECRET_ONCE_WARNING).toContain('无法再次查看');
  });
});
