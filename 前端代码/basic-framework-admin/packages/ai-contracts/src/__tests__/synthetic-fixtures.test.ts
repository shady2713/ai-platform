import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import process from 'node:process';

import { describe, expect, it } from 'vitest';

import { parseChartSpec, parseResultBlocks } from '../index';

/**
 * F10 合成业务夹具（与 Java 侧读取同一份 packages/ai-contracts/fixtures）：
 * 金额必须是十进制字符串、范围空集合表示无权限、知识文档覆盖注入与删除、Mock 响应声明期望结果。
 */
function findFixturesDir(): string {
  let dir = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    const candidate = join(dir, 'packages/ai-contracts/fixtures');
    if (existsSync(candidate)) {
      return candidate;
    }
    dir = dirname(dir);
  }
  throw new Error('未找到 packages/ai-contracts/fixtures');
}

const FIXTURES_DIR = findFixturesDir();

function load(name: string): Record<string, unknown> {
  return JSON.parse(readFileSync(join(FIXTURES_DIR, name), 'utf8')) as Record<
    string,
    unknown
  >;
}

const DECIMAL = /^-?\d+(\.\d+)?$/;

describe('合成业务夹具', () => {
  it('订单金额是十进制字符串且大额不丢精度', () => {
    const fixture = load('business-orders.json');
    const rows = fixture.rows as Array<Record<string, string>>;
    expect(rows).toHaveLength(4);
    for (const row of rows) {
      expect(row.amount).toMatch(DECIMAL);
    }
    // 字符串原样保留：不经 Number 转换
    expect(rows[0]?.amount).toBe('12345678901234.56');
    expect(Number.isSafeInteger(Number(rows[0]?.amount))).toBe(false);
  });

  it('回款夹具覆盖同一订单多笔回款', () => {
    const rows = load('business-payments.json').rows as Array<
      Record<string, string>
    >;
    expect(rows.filter((row) => row.order_id === 'ORD-2026-0002')).toHaveLength(
      2,
    );
  });

  it('范围夹具含空集合（表示无权限而不是无限制）', () => {
    const scopes = load('user-scopes.json').scopes as Array<{
      subject: { externalUserId: string };
      values: string[];
    }>;
    const empty = scopes.find((scope) => scope.values.length === 0);
    expect(empty?.subject.externalUserId).toBe('carol');
  });

  it('分页夹具含超限拒绝样例', () => {
    const fixture = load('api-pagination.json');
    expect((fixture.pages as unknown[]).length).toBe(2);
    expect((fixture.overLimit as { expected: string }).expected).toContain(
      'REJECT',
    );
  });
});

describe('知识与 Mock 模型夹具', () => {
  it('知识文档覆盖公共/私有/注入/删除', () => {
    const documents = load('knowledge-documents.json').documents as Array<
      Record<string, unknown>
    >;
    expect(documents.map((document) => document.visibility)).toContain(
      'PUBLIC',
    );
    expect(documents.map((document) => document.visibility)).toContain(
      'PRIVATE',
    );
    expect(
      documents.some((document) =>
        String(document.content).includes('忽略之前的指令'),
      ),
    ).toBe(true);
    expect(documents.some((document) => document.deleted === true)).toBe(true);
  });

  it('mock 响应声明期望结果，畸形样例确实无法解析', () => {
    const responses = load('mock-model-responses.json').responses as Array<
      Record<string, unknown>
    >;
    const expectations = new Set(responses.map((response) => response.expect));
    for (const expectation of expectations) {
      expect(['ACCEPT', 'REJECT', 'TIMEOUT', 'UPSTREAM_ERROR']).toContain(
        expectation,
      );
    }
    expect(expectations).toContain('ACCEPT');
    expect(expectations).toContain('REJECT');

    const malformed = responses.find(
      (response) => response.id === 'malformed-json',
    );
    expect(() => JSON.parse(String(malformed?.rawBody))).toThrow();
  });

  it('固定时钟与协议夹具一致', () => {
    const clock = load('fixed-clock.json');
    expect(clock.now).toBe('2026-09-17T02:00:00Z');
    // 夹具目录只包含 JSON，防止混入其他格式导致两栈解析分歧
    expect(
      readdirSync(FIXTURES_DIR).every((name) => name.endsWith('.json')),
    ).toBe(true);
  });

  it('协议夹具与业务金额规则一致（图表值可用十进制字符串）', () => {
    const chart = parseResultBlocks([
      {
        kind: 'chart',
        spec: {
          type: 'bar',
          categories: ['一月'],
          series: [{ name: '销售额', data: ['12345678901234.56'] }],
        },
      },
    ]);
    const block = chart[0];
    expect(block?.kind).toBe('chart');
    if (block?.kind === 'chart') {
      expect(parseChartSpec(block.spec).series[0]?.data).toEqual([
        '12345678901234.56',
      ]);
    }
  });
});
