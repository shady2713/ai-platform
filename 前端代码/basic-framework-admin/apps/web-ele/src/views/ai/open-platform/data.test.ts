import { describe, expect, it } from 'vitest';

import {
  AI_OPEN_PLATFORM_PERMISSIONS,
  catalogEntries,
  catalogSummary,
  catalogTags,
  exampleForCopy,
  exampleLeaksCredential,
  PLACEHOLDER_PATTERN,
} from './data';

/** O08 目录数据：只展示已发布接口、示例不含真实凭据、限额与形态可见。 */
describe('ai open platform data', () => {
  it('权限码与 V65 迁移种子一致', () => {
    expect(AI_OPEN_PLATFORM_PERMISSIONS.query).toBe('ai:open-platform:query');
  });

  it('按能力域过滤且统计形态', () => {
    const tags = catalogTags();
    expect(tags).toContain('运行');
    expect(tags).toContain('会话');
    expect(catalogEntries('运行').every((item) => item.tag === '运行')).toBe(
      true,
    );

    const all = catalogSummary();
    expect(all.total).toBeGreaterThanOrEqual(15);
    expect(all.asynchronous).toBeGreaterThanOrEqual(1);
    expect(all.sse).toBe(1);
  });

  it('示例使用占位符且不出现真实凭据', () => {
    expect(PLACEHOLDER_PATTERN.test('<TICKET>')).toBe(true);
    expect(exampleLeaksCredential(undefined)).toBe(false);
    expect(exampleLeaksCredential('{"appSecret":"<APP_SECRET>"}')).toBe(false);
    expect(exampleLeaksCredential('{"token":"aitkt_abcdefghijkl"}')).toBe(true);
    expect(exampleLeaksCredential('{"appSecret":"aiapp_realsecret"}')).toBe(
      true,
    );
    expect(
      exampleLeaksCredential('Authorization: Bearer abcdefghijklmnop'),
    ).toBe(true);

    for (const endpoint of catalogEntries()) {
      expect(exampleLeaksCredential(endpoint.requestExample)).toBe(false);
      expect(exampleLeaksCredential(endpoint.responseExample)).toBe(false);
    }
  });

  it('复制文本包含方法与示例且不含真实令牌', () => {
    const text = exampleForCopy({
      auth: 'Bearer 票据',
      asynchronous: true,
      errors: [],
      id: 'run-accept',
      limits: [],
      method: 'POST',
      path: '/app-api/ai/run/accept',
      requestExample: '{"idempotencyKey":"idem-0123456789abcdef"}',
      responseExample: '{"code":0}',
      sse: false,
      summary: '受理运行',
      tag: '运行',
    });

    expect(text).toContain('POST /app-api/ai/run/accept');
    expect(text).toContain('请求：');
    expect(text).toContain('响应：');
    expect(exampleLeaksCredential(text)).toBe(false);
  });

  it('sSE 与异步形态在目录里可辨识', () => {
    const sse = catalogEntries().filter((item) => item.sse);
    expect(sse.map((item) => item.id)).toContain('run-events');
    expect(sse[0]?.errors.join(',')).toContain('重放窗口');
    const asynchronous = catalogEntries().filter((item) => item.asynchronous);
    expect(asynchronous.map((item) => item.id)).toContain('run-accept');
  });
});
