import { describe, expect, it } from 'vitest';

import {
  AI_APPLICATION_PERMISSIONS,
  AI_FEDERATION_PERMISSIONS,
  AI_GRANT_PERMISSIONS,
  analysisScopeModeLabel,
  CROSS_SYSTEM_SELECTION_HINT,
  EXACT_ORIGIN_PATTERN,
  FEDERATION_APPROVAL_HINT,
  FEDERATION_NO_INFERENCE_HINT,
  federationIdentityText,
  federationStatusLabel,
  formatScopeRow,
  parseOrigins,
  scopeSummary,
  SECRET_ONCE_WARNING,
  useDiscoveryFormSchema,
  useFederationFormSchema,
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

  it('declares the federation permission code shipped by V91', () => {
    expect(AI_FEDERATION_PERMISSIONS).toEqual({
      manage: 'ai:application:federation',
    });
  });

  it('labels scope modes and federation states without inventing semantics', () => {
    expect(analysisScopeModeLabel('CROSS_SYSTEM')).toBe('跨系统分析');
    expect(analysisScopeModeLabel('CURRENT_SYSTEM')).toBe('仅当前系统');
    expect(analysisScopeModeLabel('UNKNOWN')).toBe('UNKNOWN');
    expect(federationStatusLabel('APPROVED')).toContain('参与发现');
    expect(federationStatusLabel('PENDING')).toContain('不参与发现');
    expect(federationStatusLabel('REVOKED')).toContain('不参与发现');
    expect(federationStatusLabel('WEIRD')).toBe('WEIRD');
  });

  it('summarizes scope rows and identities verbatim', () => {
    expect(
      formatScopeRow({
        actions: ['READ', 'EXECUTE'],
        resourceKey: 'q3',
        resourceType: 'REPORT',
      }),
    ).toBe('REPORT/q3（READ/EXECUTE）');
    expect(
      formatScopeRow({
        actions: [],
        resourceKey: 'q3',
        resourceType: 'REPORT',
      }),
    ).toBe('REPORT/q3');
    expect(
      scopeSummary({
        appCode: 'crm',
        applicationId: 5,
        currentSystem: true,
        externalUserId: 'alice',
        federationId: null,
        federationRevision: null,
        scopeSource: 'crm-auth',
        scopeVersion: 1,
        scopes: [],
        subjectType: 'USER',
        systemFingerprint: 'fp',
        systemName: 'CRM',
      }),
    ).toBe('无可访问范围');
    expect(
      federationIdentityText({
        approvedBy: null,
        id: 42,
        requestedBy: 1001,
        revision: 1,
        sourceApplicationId: 5,
        sourceExternalUserId: 'alice',
        sourceSubjectType: 'USER',
        status: 'PENDING',
        targetApplicationId: 9,
        targetExternalUserId: 'bob',
        targetSubjectType: 'USER',
        version: 0,
      }),
    ).toBe('USER:alice → USER:bob');
    expect(
      federationIdentityText({
        approvedBy: null,
        id: 42,
        requestedBy: 1001,
        revision: 1,
        sourceApplicationId: 5,
        sourceExternalUserId: '',
        sourceSubjectType: 'APP',
        status: 'PENDING',
        targetApplicationId: 9,
        targetExternalUserId: '',
        targetSubjectType: 'APP',
        version: 0,
      }),
    ).toBe('APP 主体 → APP 主体');
  });

  it('keeps the independent-approval and no-inference warnings explicit', () => {
    expect(FEDERATION_APPROVAL_HINT).toContain('独立审批');
    expect(FEDERATION_APPROVAL_HINT).toContain('批准人由服务端');
    expect(FEDERATION_NO_INFERENCE_HINT).toContain('不代表');
    expect(CROSS_SYSTEM_SELECTION_HINT).toContain('目录指纹');
    // 发现与联邦表单都不提供"自动匹配同名用户"的字段
    const discoveryFields = useDiscoveryFormSchema().map(
      (item) => item.fieldName,
    );
    expect(discoveryFields).toEqual(['subjectType', 'externalUserId']);
    const federationFields = useFederationFormSchema().map(
      (item) => item.fieldName,
    );
    expect(federationFields).not.toContain('autoMatch');
    expect(federationFields).toContain('targetExternalUserId');
  });
});
