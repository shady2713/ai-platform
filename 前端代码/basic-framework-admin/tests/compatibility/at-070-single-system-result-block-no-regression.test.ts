import type { TableBlock } from '../../packages/ai-chat-ui/src/message/blocks';

/**
 * Y06 反向回归（前端）：V2 跨源链**没有改变**既有单系统结果块的解析与渲染。
 *
 * <p>为什么必须专门做反向用例：Y05 为跨源授权完整性改过两个**既有**文件——
 * `packages/ai-chat-ui/src/message/ResultTable.vue` 与 `blocks.ts`。它们同时服务旧的
 * 单系统表格块。改共享渲染层最典型的回退有三种，且**正向的跨源用例全绿**也照样发生：
 * <ol>
 *   <li>旧表格块没带 `crossSourceIntegrity` 时被误判成"被拒"→ 数字整段消失（最严重：看起来像故障）；</li>
 *   <li>旧的 `completeness`（技术完整性）图注被跨源的授权完整性提示顶掉 → 语义被改写；</li>
 *   <li>分页条数 `pageInfo.total` 在不该隐藏时不再渲染 → 既有信息丢失。</li>
 * </ol>
 *
 * <p>因此每条用例都用**不带** `crossSourceIntegrity` 的旧单系统载荷驱动真实的
 * `parseTableBlock` / `ResultTable`，断言渲染结果与 V2 上线前逐字段一致。
 * 断言对象是产品源码本身（`blocks.ts` / `ResultTable.vue` / `cross-source-integrity.ts`），
 * 不复制判定逻辑，也不打桩。
 */
import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import { parseMessageBlock } from '../../packages/ai-chat-ui/src/message/blocks';
import {
  CROSS_SOURCE_WITHHELD,
  rendersNothing,
} from '../../packages/ai-chat-ui/src/message/cross-source-integrity';
import ResultTable from '../../packages/ai-chat-ui/src/message/ResultTable.vue';

/**
 * 旧单系统表格块：V2 之前后端只会产出这些字段。
 *
 * <p>注意这里**故意不带** `crossSourceIntegrity`——这正是"升级前的老载荷"的形态，
 * 也是最容易在共享渲染层被误伤的那一类输入。
 */
const LEGACY_TABLE_PAYLOAD = {
  kind: 'table',
  columns: [
    { field: 'customer_name', label: '客户' },
    { field: 'net_amount', label: '净额', unit: 'CNY' },
  ],
  rows: [
    { customer_name: 'bob', net_amount: '450.00' },
    { customer_name: 'alice', net_amount: '290.00' },
  ],
  pageInfo: { page: 1, size: 20, total: 2 },
} as const;

/** 经**生产公开入口** `parseMessageBlock` 解析旧单系统载荷（不走内部私有函数）。 */
function legacyTable(): TableBlock {
  return parseMessageBlock(structuredClone(LEGACY_TABLE_PAYLOAD)) as TableBlock;
}

/** 解析并断言拿到的是表格块（避免用 as 掩盖"解析器返回了别的东西"）。 */
function parseTable(payload: unknown): TableBlock {
  const parsed = parseMessageBlock(payload);
  if (parsed.kind !== 'table') {
    throw new Error(`期望 table 块，实际解析出 ${parsed.kind}`);
  }
  return parsed;
}

function renderTable(block: TableBlock) {
  return mount(ResultTable, { props: { block } });
}

describe('y06 反向回归：单系统结果块在 V2 上线后逐字段不变', () => {
  it('旧载荷解析后不凭空多出 crossSourceIntegrity 字段', () => {
    const block = legacyTable();

    // 字段缺失必须仍是"缺失"，不得被默认成 COMPLETE：那等于前端替后端宣布"你有权看"
    expect(block.crossSourceIntegrity).toBeUndefined();
    expect(rendersNothing(block.crossSourceIntegrity)).toBe(false);
    expect(Object.keys(block).toSorted()).toEqual(
      ['columns', 'kind', 'pageInfo', 'rows'].toSorted(),
    );
  });

  it('旧载荷的每一行、每一列原样解析：金额仍是精确文本，不经浮点', () => {
    const block = legacyTable();

    expect(block.rows).toEqual([
      { customer_name: 'bob', net_amount: '450.00' },
      { customer_name: 'alice', net_amount: '290.00' },
    ]);
    // D11 黄金集金额逐位一致：450.00 / 290.00（字符串相等，不是数字近似）
    expect(block.rows.map((row) => row.net_amount)).toEqual([
      '450.00',
      '290.00',
    ]);
    expect(block.pageInfo).toEqual({ page: 1, size: 20, total: 2 });
  });

  it('旧载荷照旧渲染表格、分页与行数：不出现任何跨源提示', () => {
    const wrapper = renderTable(legacyTable());

    expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
      true,
    );
    expect(
      wrapper.find('[data-testid="ai-message-table-withheld"]').exists(),
    ).toBe(false);
    // 分页条数是旧版就有的信息，V2 上线后不得消失
    expect(
      wrapper.find('[data-testid="ai-message-table-page"]').text(),
    ).toContain('共 2 条');
    expect(wrapper.findAll('tbody tr')).toHaveLength(2);
    expect(wrapper.get('th[data-field="net_amount"]').text()).toBe(
      '净额（CNY）',
    );
    // 金额原样展示：不被本地化成 450
    expect(wrapper.get('td[data-field="net_amount"]').text()).toBe('450.00');
  });

  it('completeness（技术完整性）语义未被跨源授权完整性改写', () => {
    // 旧的截断语义：PARTIAL 仍只出"结果可能不完整"图注
    const partial = parseTable({
      ...structuredClone(LEGACY_TABLE_PAYLOAD),
      completeness: 'PARTIAL',
    } as never);
    const wrapper = renderTable(partial);

    expect(
      wrapper.get('[data-testid="ai-message-table-completeness"]').text(),
    ).toContain('数据完整性：PARTIAL（结果可能不完整）');
    // 两种完整性是不同的事：没有 crossSourceIntegrity 就不该冒出跨源图注
    expect(
      wrapper.find('[data-testid="ai-message-table-cross-source"]').exists(),
    ).toBe(false);
    expect(
      wrapper.find('[data-testid="ai-message-table-withheld"]').exists(),
    ).toBe(false);
  });

  it('cOMPLETE 仍然不出完整性图注（与升级前一致）', () => {
    const complete = parseTable({
      ...structuredClone(LEGACY_TABLE_PAYLOAD),
      completeness: 'COMPLETE',
    } as never);
    const wrapper = renderTable(complete);

    expect(
      wrapper.find('[data-testid="ai-message-table-completeness"]').exists(),
    ).toBe(false);
    expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
      true,
    );
  });

  it('空结果仍是"没有可显示的数据行"，不被改写成授权拒绝', () => {
    const empty = parseTable({
      kind: 'table',
      columns: [{ field: 'customer_name', label: '客户' }],
      rows: [],
      completeness: 'COMPLETE',
    } as never);
    const wrapper = renderTable(empty);

    expect(wrapper.get('[data-testid="ai-message-table-empty"]').text()).toBe(
      '没有可显示的数据行',
    );
    // 空结果与"被授权拒绝"是两件事：后者连表格都不出
    expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
      true,
    );
    expect(
      wrapper.find('[data-testid="ai-message-table-withheld"]').exists(),
    ).toBe(false);
  });

  it('（对照）只有显式 WITHHELD 才隐藏数字：证明上一条不是被清空', () => {
    // 这条是反向对照组：确保"旧载荷照旧渲染"不是因为组件根本没渲染表格，
    // 而是因为带 WITHHELD 时确实什么都不出。
    const withheld = parseTable({
      ...structuredClone(LEGACY_TABLE_PAYLOAD),
      crossSourceIntegrity: CROSS_SOURCE_WITHHELD,
    } as never);
    const wrapper = renderTable(withheld);

    expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="ai-message-table-page"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.get('[data-testid="ai-message-table-withheld"]').text(),
    ).toContain('该结果未出具');
  });
});
