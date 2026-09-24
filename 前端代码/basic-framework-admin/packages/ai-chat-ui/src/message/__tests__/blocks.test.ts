import { describe, expect, it } from 'vitest';

import {
  cellDisplay,
  isActionExpired,
  parseMessageBlock,
  sourceKindOf,
  toRenderableBlock,
  toRenderableBlocks,
} from '../blocks';

const CITATION = {
  chunkIndex: 2,
  citationId: 'kb_1:12:3',
  documentId: 9,
  kind: 'citation',
  locationRef: '第 3 页',
  snippet: '片段正文',
  title: '销售手册',
  versionNo: 2,
};

const FILE = {
  businessKey: 'ai_chat_session:1',
  businessType: 'ai_chat_session',
  fileId: 7,
  kind: 'file',
  mime: 'text/markdown',
  name: '说明.md',
  size: 2048,
};

describe('消息块目录', () => {
  it('冻结 v1 的 text/chart/error 交给 @vben/ai-contracts 解析', () => {
    expect(parseMessageBlock({ kind: 'text', text: '你好' })).toStrictEqual({
      kind: 'text',
      text: '你好',
    });
    const chart = parseMessageBlock({
      kind: 'chart',
      spec: {
        categories: ['a'],
        series: [{ data: ['12345678901234.56'], name: '销售额' }],
        title: '月度',
        type: 'bar',
      },
    });
    expect(chart.kind).toBe('chart');
    expect(parseMessageBlock({ kind: 'error', message: '失败' })).toStrictEqual(
      {
        kind: 'error',
        message: '失败',
      },
    );
  });

  it('冻结 v1 的形状错误被拒绝（不由本层兜底修复）', () => {
    expect(() => parseMessageBlock({ kind: 'text' })).toThrow(
      /不符合冻结 v1 的 text 块/u,
    );
    expect(() =>
      parseMessageBlock({
        categories: ['a'],
        kind: 'chart',
        series: [{ data: [{ amount: 1 }], name: 's' }],
        type: 'line',
      }),
    ).toThrow(/不符合冻结 v1 的 chart 块/u);
  });

  it('解析首期块：table / report / citation / file / action / clarification', () => {
    const table = parseMessageBlock({
      columns: [
        { field: 'region', label: '区域' },
        { field: 'amount', label: '金额', unit: '元' },
      ],
      completeness: 'PARTIAL',
      kind: 'table',
      pageInfo: { page: 1, size: 20, total: 100 },
      rows: [{ amount: '12345678901234.56', region: '华东' }],
    });
    expect(table.kind).toBe('table');
    expect(cellDisplay('12345678901234.56')).toBe('12345678901234.56');

    const report = parseMessageBlock({
      kind: 'report',
      reportId: 'rpt_sales01',
      title: '销售报表',
      version: 3,
    });
    expect(report.kind === 'report' && report.version).toBe(3);

    expect(parseMessageBlock(CITATION).kind).toBe('citation');
    expect(parseMessageBlock(FILE).kind).toBe('file');

    const action = parseMessageBlock({
      actionId: 'act_1',
      expiresAt: '2026-09-24T10:00:00Z',
      kind: 'action',
      parameterSummary: '将 A 表 3 行更新为已归档',
      toolName: 'updateRows',
    });
    expect(action.kind === 'action' && action.toolName).toBe('updateRows');

    const clarification = parseMessageBlock({
      fields: [{ label: '起始日期', name: 'start_date', required: true }],
      kind: 'clarification',
      options: ['本月', '上月'],
      question: '要哪个区间？',
    });
    expect(
      clarification.kind === 'clarification' && clarification.options,
    ).toHaveLength(2);
  });

  it('未知类型与非法取值被拒绝', () => {
    expect(() => parseMessageBlock({ html: '<b>x</b>', kind: 'html' })).toThrow(
      /未知结果类型 html/u,
    );
    expect(() => parseMessageBlock({ kind: 'table' })).toThrow(/数组长度/u);
    expect(() => parseMessageBlock({ ...CITATION, extra: 1 })).toThrow(
      /未知字段 extra/u,
    );
    expect(() => parseMessageBlock({ ...FILE, fileId: 0 })).toThrow(
      /整数越界/u,
    );
    expect(() => parseMessageBlock({ ...FILE, name: 'bad\u0007name' })).toThrow(
      /控制字符/u,
    );
    expect(() =>
      parseMessageBlock({ ...CITATION, citationId: '有 空格' }),
    ).toThrow(/引用标识不合法/u);
    expect(() =>
      parseMessageBlock({ kind: 'report', reportId: 'rpt_x', version: 1 }),
    ).toThrow(/报表标识不合法/u);
    expect(() =>
      parseMessageBlock({
        actionId: 'act_1',
        expiresAt: '2026-09-24 10:00:00',
        kind: 'action',
        parameterSummary: 'x',
        toolName: 't',
      }),
    ).toThrow(/到期时间不合法/u);
    expect(() =>
      parseMessageBlock({
        kind: 'clarification',
        options: [],
        question: '',
      }),
    ).toThrow(/文本长度不合法/u);
    expect(() => parseMessageBlock('text')).toThrow(/必须是对象/u);
    expect(() => parseMessageBlock({ text: 'x' })).toThrow(/缺少 kind/u);
    expect(() => parseMessageBlock({ kind: '' })).toThrow(/缺少 kind/u);
  });

  it('report 块内联规格复用报表层解析（非法即拒绝，不交给渲染器）', () => {
    expect(() =>
      parseMessageBlock({
        kind: 'report',
        reportId: 'rpt_sales01',
        spec: { blocks: [], schemaVersion: '2.0', title: 'x' },
        version: 1,
      }),
    ).toThrow(/报表数据不合法/u);
  });

  it('表格行不是对象时拒绝', () => {
    expect(() =>
      parseMessageBlock({
        columns: [{ field: 'a', label: 'A' }],
        kind: 'table',
        rows: ['不是对象'],
      }),
    ).toThrow(/结果行必须是对象/u);
  });

  it('降级：未知类型保留来源类型与原因，绝不猜测渲染', () => {
    const degraded = toRenderableBlock({ html: '<b>x</b>', kind: 'html' });
    expect(degraded.kind).toBe('unsupported');
    expect(degraded.kind === 'unsupported' && degraded.sourceKind).toBe('html');
    expect(degraded.kind === 'unsupported' && degraded.reason).toContain(
      '未知结果类型',
    );

    const ok = toRenderableBlock(FILE);
    expect(ok.kind).toBe('supported');

    const detached = toRenderableBlock('abc');
    expect(detached.kind === 'unsupported' && detached.sourceKind).toBe(
      'string',
    );

    expect(toRenderableBlocks([FILE, { kind: 'weird' }])).toHaveLength(2);
  });

  it('来源类型：kind 优先，平台草案的 type 次之', () => {
    expect(sourceKindOf({ kind: 'table' })).toBe('table');
    expect(sourceKindOf({ type: 'clarification' })).toBe('clarification');
    expect(sourceKindOf(42)).toBe('number');
  });

  it('空值单元格显示为 — 而不是 0', () => {
    expect(cellDisplay(null)).toBe('—');
    expect(cellDisplay(undefined)).toBe('—');
    expect(cellDisplay(0)).toBe('0');
    expect(cellDisplay('')).toBe('');
    expect(cellDisplay({ a: 1 })).toBe('{"a":1}');
  });

  it('动作过期判定：状态优先，其次看到期时间（已处理不算过期）', () => {
    const base = {
      actionId: 'act_1',
      expiresAt: '2026-09-24T10:00:00Z',
      kind: 'action' as const,
      parameterSummary: 'x',
      toolName: 't',
    };
    const cutoff = Date.parse('2026-09-24T09:00:00Z');
    expect(isActionExpired(base, cutoff)).toBe(false);
    expect(isActionExpired(base, Date.parse('2026-09-24T11:00:00Z'))).toBe(
      true,
    );
    expect(isActionExpired({ ...base, status: 'EXPIRED' }, cutoff)).toBe(true);
    // 已取消/已确认属于"已处理"，不是过期（提示语义不同，由渲染层先判）
    expect(isActionExpired({ ...base, status: 'CANCELLED' }, cutoff)).toBe(
      false,
    );
    expect(isActionExpired({ ...base, status: 'CONFIRMED' }, cutoff)).toBe(
      false,
    );
    expect(isActionExpired({ ...base, expiresAt: 'not-a-date' }, cutoff)).toBe(
      false,
    );
  });
});
