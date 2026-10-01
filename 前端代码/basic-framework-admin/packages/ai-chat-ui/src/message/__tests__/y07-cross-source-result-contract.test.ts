import type { TableBlock } from '../blocks';

/**
 * Y07 专项：跨源结果契约的 fail-closed 规则。
 *
 * <p>本文件专门钉 AT-071 的三条专项，其中**第一条是本卡全部价值所在**：
 * 跨源响应**缺失完整性口径**时，必须按 `WITHHELD` 渲染，绝不按 `COMPLETE`。
 *
 * <p>为什么这条只能用反向测试证明：正常路径（后端老老实实发了口径）下，
 * fail-open 与 fail-closed 的渲染结果**完全一样**——都能正确显示或不显示。
 * 两者的差别只在"后端没发"这一条路径上，而这条路径不会自己出现，
 * 必须**手工构造一个故意不带 {@code crossSourceIntegrity} 的跨源响应**。
 * 只跑正常路径等于没做这条验收。
 *
 * <p>断言对象是产品源码本身（`blocks.ts` / `ResultTable.vue` /
 * `cross-source-integrity.ts`），不复制判定逻辑，也不打桩。
 */
import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import { parseMessageBlock } from '../blocks';
import {
  CROSS_SOURCE_INTEGRITY_MISSING,
  CROSS_SOURCE_WITHHELD,
  rendersNothing,
} from '../cross-source-integrity';
import ResultTable from '../ResultTable.vue';

const COLUMNS = [
  { field: 'amount', label: '金额', unit: '元' },
  { field: 'customer_key', label: '客户' },
];

const ROWS = [
  { amount: '100.00', customer_key: 'C-001' },
  { amount: '30.00', customer_key: 'C-002' },
];

/**
 * 跨源响应骨架：带 `crossSource: true` 标记，**故意不带** `crossSourceIntegrity`。
 *
 * <p>形态取自后端 `AiCrossSourceMergeRespVO` 的真实字段名（`crossSource` /
 * `crossSourceIntegrity` / `totalAmount` / `sourceCount`），因此这里断言的
 * 就是真实响应漏发口径时前端的行为，而不是一个虚构载荷的行为。
 */
function crossSourceResponseWithoutIntegrity() {
  return {
    columns: COLUMNS,
    kind: 'table' as const,
    pageInfo: { page: 1, size: 20, total: 2 },
    rows: ROWS,
    crossSource: true,
  };
}

function parseTable(payload: unknown): TableBlock {
  const parsed = parseMessageBlock(payload);
  if (parsed.kind !== 'table') {
    throw new Error(`期望 table 块，实际解析出 ${parsed.kind}`);
  }
  return parsed;
}

/** 渲染整块并取可见文本（断言"界面上一个数字都没有"时统一走这里）。 */
function wrapperText(block: TableBlock): string {
  return mount(ResultTable, { props: { block } }).text();
}

describe('y07 AT-071 专项一（反向）：跨源响应缺失完整性口径按 WITHHELD 处理', () => {
  it('带 crossSource 标记但缺口径 → 解析结果就是 WITHHELD，不是 COMPLETE', () => {
    const block = parseTable(
      structuredClone(crossSourceResponseWithoutIntegrity()),
    );

    // 这是整条验收的核心断言：缺口径绝不能落到 COMPLETE
    expect(block.crossSourceIntegrity).toEqual(CROSS_SOURCE_INTEGRITY_MISSING);
    expect(block.crossSourceIntegrity?.state).not.toBe('COMPLETE');
    expect(block.crossSourceIntegrity?.state).toBe('WITHHELD');
    expect(rendersNothing(block.crossSourceIntegrity)).toBe(true);
  });

  it('缺口径的跨源响应：一个数字都不渲染（表格/行/条数全无）', () => {
    const wrapper = mount(ResultTable, {
      props: {
        block: parseTable(
          structuredClone(crossSourceResponseWithoutIntegrity()),
        ),
      },
    });

    expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
      false,
    );
    // 条数是信道：留着 pageInfo.total 就能反推规模
    expect(wrapper.find('[data-testid="ai-message-table-page"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.find('[data-testid="ai-message-table-empty"]').exists(),
    ).toBe(false);
    expect(wrapper.text()).not.toContain('100.00');
    expect(wrapper.text()).not.toContain('30.00');
    expect(wrapper.text()).not.toContain('C-001');
    expect(
      wrapper.find('[data-testid="ai-message-table-withheld"]').exists(),
    ).toBe(true);
  });

  it('缺口径的提示必须说清"不是缺数据、而是没给出口径"，且不得点名来源', () => {
    const block = parseTable(
      structuredClone(crossSourceResponseWithoutIntegrity()),
    );
    const wrapper = mount(ResultTable, { props: { block } });

    const notice = wrapper
      .get('[data-testid="ai-message-table-withheld"]')
      .text();
    expect(notice).toContain('该结果未出具');
    expect(notice).toContain('未声明');
    // 不得回显任何被禁来源标识
    expect(notice).not.toMatch(/payment|回款|来源\s*\d/);
  });

  it('（对照）后端真的发了 COMPLETE 时才放行——证明上一条不是"永远不出具"', () => {
    // 反向对照组：如果带 COMPLETE 也什么都不渲染，那上一条就只是组件坏了
    const block = parseTable({
      ...structuredClone(crossSourceResponseWithoutIntegrity()),
      crossSourceIntegrity: { state: 'COMPLETE' },
    } as never);
    const wrapper = mount(ResultTable, { props: { block } });

    expect(block.crossSourceIntegrity?.state).toBe('COMPLETE');
    expect(rendersNothing(block.crossSourceIntegrity)).toBe(false);
    expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
      true,
    );
    expect(wrapper.text()).toContain('100.00');
    expect(
      wrapper.find('[data-testid="ai-message-table-page"]').text(),
    ).toContain('共 2 条');
  });

  it('crossSource 必须是真布尔值：字符串 "true" 不算跨源标记', () => {
    // 隐式转换会让一个拼错的标记静默退化成单系统响应，正是本卡要消灭的 fail-open
    expect(() =>
      parseTable({
        ...structuredClone(crossSourceResponseWithoutIntegrity()),
        crossSource: 'true',
      } as never),
    ).toThrow();
  });

  it('字段存在但状态认不出仍然解析期拒绝（Y05 语义不被削弱）', () => {
    // 缺字段 → WITHHELD；状态认不出 → 整块拒绝。两者刻意不同向：
    // 前者是"平台没证明你有权看"，后者是"契约漂移"，排查方向不一样。
    expect(() =>
      parseTable({
        ...structuredClone(crossSourceResponseWithoutIntegrity()),
        crossSourceIntegrity: { state: 'SUPER_VISIBLE' },
      } as never),
    ).toThrow();
  });

  it('口径为 PARTIAL 时仍然出表（角色只允许看合计的场景）', () => {
    const block = parseTable({
      ...structuredClone(crossSourceResponseWithoutIntegrity()),
      crossSourceIntegrity: {
        reason: '调用方角色不允许查看分来源明细',
        state: 'PARTIAL',
      },
    } as never);
    const wrapper = mount(ResultTable, { props: { block } });

    expect(rendersNothing(block.crossSourceIntegrity)).toBe(false);
    expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
      true,
    );
    expect(
      wrapper.find('[data-testid="ai-message-table-cross-source"]').text(),
    ).toContain('PARTIAL');
  });
});

describe('y07 AT-071 专项二（反向）：无权来源在响应中不可反推明细', () => {
  /**
   * 被拒的跨源响应：数据行与分页条数都**在载荷里**，口径为 `WITHHELD`。
   *
   * <p>这里断言的是渲染层：载荷里有数字不等于界面上有数字。
   * 合计与来源计数的"结构性抹除"是后端契约的职责，由
   * `CrossSourceResultContractTest` 断言；本文件钉住的是
   * "即便这些量被塞进了结果块，界面也不许渲染"。
   */
  function withheldCrossSourceResponse() {
    return {
      columns: COLUMNS,
      kind: 'table' as const,
      pageInfo: { page: 1, size: 20, total: 2 },
      rows: ROWS,
      crossSource: true,
      crossSourceIntegrity: CROSS_SOURCE_WITHHELD,
    };
  }

  it('第一条腿（差额可解）：被拒时不渲染任何数据行，界面上没有可相减的金额', () => {
    const block = parseTable(structuredClone(withheldCrossSourceResponse()));

    expect(wrapperText(block)).not.toContain('100.00');
    expect(wrapperText(block)).not.toContain('30.00');
    expect(wrapperText(block)).not.toContain('C-001');
    expect(
      mount(ResultTable, { props: { block } })
        .find('[data-testid="ai-message-table"]')
        .exists(),
    ).toBe(false);
  });

  it('第二条腿（条数可数）：被拒时不渲染分页条数，调用方数不出来源个数', () => {
    const block = parseTable(structuredClone(withheldCrossSourceResponse()));
    const wrapper = mount(ResultTable, { props: { block } });

    // pageInfo.total 留在载荷里（解析器不销毁它），但绝不渲染：
    // 留着它，用户就能反推被禁来源的规模
    expect(block.pageInfo?.total).toBe(2);
    expect(wrapper.find('[data-testid="ai-message-table-page"]').exists()).toBe(
      false,
    );
    expect(wrapper.text()).not.toContain('共 2 条');
  });

  it('wITHHELD 的整段文案里不出现任何来源角色名', () => {
    const block = parseTable({
      columns: COLUMNS,
      kind: 'table',
      rows: [{ amount: '999.00', customer_key: 'payment' }],
      crossSource: true,
      crossSourceIntegrity: CROSS_SOURCE_WITHHELD,
    } as never);

    expect(wrapperText(block)).not.toContain('payment');
    expect(wrapperText(block)).not.toContain('999.00');
  });
});

describe('y07 AT-071 专项三（反向）：单系统响应逐字段无差异', () => {
  /**
   * 旧单系统载荷：V2 之前后端只会产出这些字段，**不带** `crossSource`。
   *
   * <p>这正是"升级前的老载荷"形态，也是共享渲染层最容易被误伤的输入。
   */
  const LEGACY_SINGLE_SYSTEM = {
    columns: COLUMNS,
    kind: 'table' as const,
    pageInfo: { page: 1, size: 20, total: 2 },
    rows: ROWS,
  };

  it('单系统载荷解析后不凭空多出任何跨源字段', () => {
    const block = parseTable(structuredClone(LEGACY_SINGLE_SYSTEM));

    expect(block.crossSource).toBeUndefined();
    expect(block.crossSourceIntegrity).toBeUndefined();
    expect(rendersNothing(block.crossSourceIntegrity)).toBe(false);
    // 键集合与 Y06 兼容测试 at-070 的断言逐字一致：不得因为新增字段变形
    expect(Object.keys(block).toSorted()).toEqual(
      ['columns', 'kind', 'pageInfo', 'rows'].toSorted(),
    );
  });

  it('单系统载荷照旧渲染表格、分页与条数：数字一个不少', () => {
    const wrapper = mount(ResultTable, {
      props: { block: parseTable(structuredClone(LEGACY_SINGLE_SYSTEM)) },
    });

    expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
      true,
    );
    expect(
      wrapper.find('[data-testid="ai-message-table-withheld"]').exists(),
    ).toBe(false);
    expect(
      wrapper.find('[data-testid="ai-message-table-cross-source"]').exists(),
    ).toBe(false);
    expect(
      wrapper.find('[data-testid="ai-message-table-page"]').text(),
    ).toContain('共 2 条');
    expect(wrapper.text()).toContain('100.00');
    expect(wrapper.get('td[data-field="customer_key"]').text()).toBe('C-001');
  });

  it('单系统标记为 false 与"不带标记"行为一致（显式否认跨源）', () => {
    const block = parseTable({
      ...structuredClone(LEGACY_SINGLE_SYSTEM),
      crossSource: false,
    } as never);
    const wrapper = mount(ResultTable, { props: { block } });

    expect(block.crossSource).toBe(false);
    expect(block.crossSourceIntegrity).toBeUndefined();
    expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
      true,
    );
  });

  it('y05 时期的载荷（带口径、不带标记）仍照常解析，不被降级成单系统', () => {
    // Y05 的 UI 测试与线上历史响应都是这个形态：不能因为后端还没开始发标记
    // 就把它当成"没有口径"而判成 WITHHELD——那会让原本有权看的用户看不到结果。
    const block = parseTable({
      ...structuredClone(LEGACY_SINGLE_SYSTEM),
      crossSourceIntegrity: CROSS_SOURCE_WITHHELD,
    } as never);

    expect(block.crossSource).toBeUndefined();
    expect(block.crossSourceIntegrity?.state).toBe('WITHHELD');
    expect(rendersNothing(block.crossSourceIntegrity)).toBe(true);
  });
});
