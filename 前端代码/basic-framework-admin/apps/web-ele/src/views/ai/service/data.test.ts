import { describe, expect, it } from 'vitest';

import {
  AI_SERVICE_PERMISSIONS,
  currentRelease,
  describeReleaseContent,
  describeRollbackImpact,
  RELEASE_STATUS_LABELS,
  SERVICE_STATUS_LABELS,
  validateJsonObjectSchema,
} from './data';

function release(
  overrides: Partial<{
    contentHash: string;
    endpointConfigRevision: number;
    evalThreshold: number;
    id: number;
    releaseVersion: number;
    status: string;
  }>,
) {
  return {
    contentHash: 'a'.repeat(64),
    endpointConfigRevision: 3,
    evalThreshold: 80,
    id: 21,
    modelEndpointId: 1,
    releaseVersion: 1,
    requiredCapabilities: 'TEXT',
    serviceId: 9,
    status: 'CANDIDATE',
    version: 0,
    ...overrides,
  };
}

describe('ai service data helpers', () => {
  it('权限码与迁移种子一致', () => {
    expect(AI_SERVICE_PERMISSIONS.activate).toBe('ai:service:activate');
    expect(AI_SERVICE_PERMISSIONS.debug).toBe('ai:service:debug');
    expect(AI_SERVICE_PERMISSIONS.evaluate).toBe('ai:service:evaluate');
    expect(AI_SERVICE_PERMISSIONS.release).toBe('ai:service:release');
    expect(AI_SERVICE_PERMISSIONS.query).toBe('ai:service:query');
  });

  it('jSON Schema 即时校验只接受 JSON 对象', () => {
    expect(validateJsonObjectSchema('{"type":"object"}')).toEqual({ ok: true });
    expect(
      validateJsonObjectSchema('  {"type":"object","properties":{}}  '),
    ).toEqual({
      ok: true,
    });
    expect(validateJsonObjectSchema('').message).toContain('不能为空');
    expect(validateJsonObjectSchema('   ').message).toContain('不能为空');
    expect(validateJsonObjectSchema('not-json').message).toContain(
      '不是合法 JSON',
    );
    expect(validateJsonObjectSchema('[1,2]').message).toContain(
      '必须是 JSON 对象',
    );
    expect(validateJsonObjectSchema('"text"').message).toContain(
      '必须是 JSON 对象',
    );
    expect(validateJsonObjectSchema('null').message).toContain(
      '必须是 JSON 对象',
    );
  });

  it('当前生效版本取唯一 ACTIVE 版本', () => {
    const releases = [
      release({ id: 22, releaseVersion: 2, status: 'CANDIDATE' }),
      release({ id: 21, releaseVersion: 1, status: 'ACTIVE' }),
    ];
    expect(currentRelease(releases)?.releaseVersion).toBe(1);
    expect(currentRelease([])).toBeUndefined();
    expect(currentRelease([release({ status: 'RETIRED' })])).toBeUndefined();
  });

  it('回退影响说明覆盖后续运行与当前权限语义', () => {
    const releases = [
      release({ id: 22, releaseVersion: 2, status: 'ACTIVE' }),
      release({ id: 21, releaseVersion: 1, status: 'RETIRED' }),
    ];

    const impact = describeRollbackImpact(releases, 21);
    expect(impact).toContain('v2 → v1');
    expect(impact).toContain('只影响后续新运行');
    expect(impact).toContain('已固定版本的会话仍按原版本执行');
    expect(impact).toContain('历史版本不会恢复旧权限');
    expect(impact).toContain('未通过时不改变当前生效版本');
    expect(describeRollbackImpact(releases, 999)).toContain('目标版本不存在');
    expect(describeRollbackImpact([], 21)).toContain('目标版本不存在');
    expect(
      describeRollbackImpact([release({ id: 21, status: 'RETIRED' })], 21),
    ).toContain('当前无生效版本');
  });

  it('版本内容说明给出摘要与不可变性', () => {
    const text = describeReleaseContent(release({}));
    expect(text).toContain('aaaaaaaaaaaa…');
    expect(text).toContain('端点配置版本 v3');
    expect(text).toContain('评测门槛 80');
    expect(text).toContain('发布内容写入后不可修改');
  });

  it('状态文案覆盖后端词表', () => {
    expect(RELEASE_STATUS_LABELS.ACTIVE).toBe('生效中');
    expect(RELEASE_STATUS_LABELS.CANDIDATE).toContain('候选');
    expect(RELEASE_STATUS_LABELS.RETIRED).toBe('已退役');
    expect(SERVICE_STATUS_LABELS.READY).toBe('可发布');
    expect(SERVICE_STATUS_LABELS.DRAFT).toBe('草稿');
  });
});
