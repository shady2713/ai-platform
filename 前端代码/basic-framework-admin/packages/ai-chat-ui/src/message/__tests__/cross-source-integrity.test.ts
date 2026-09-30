import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import { toRenderableBlocks } from '../blocks';
import {
  CROSS_SOURCE_WITHHELD,
  integrityNotice,
  rendersNothing,
} from '../cross-source-integrity';
import ResultTable from '../ResultTable.vue';

const COLUMNS = [
  { field: 'amount', label: '金额', unit: '元' },
  { field: 'customer_key', label: '客户' },
];

function table(overrides: Record<string, unknown> = {}) {
  return {
    columns: COLUMNS,
    kind: 'table' as const,
    rows: [
      { amount: '100.00', customer_key: 'C-001' },
      { amount: '30.00', customer_key: 'C-002' },
    ],
    ...overrides,
  };
}

describe('跨源完整性口径（Y05）', () => {
  describe('正向：有权的结果照常渲染', () => {
    it('cOMPLETE 状态渲染表格、数据行与条数', () => {
      const wrapper = mount(ResultTable, {
        props: {
          block: table({
            crossSourceIntegrity: { state: 'COMPLETE' },
            pageInfo: { page: 1, size: 20, total: 2 },
          }) as never,
        },
      });

      expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
        true,
      );
      expect(wrapper.text()).toContain('100.00');
      expect(
        wrapper.find('[data-testid="ai-message-table-page"]').text(),
      ).toContain('共 2 条');
      expect(rendersNothing({ state: 'COMPLETE' })).toBe(false);
    });

    it('pARTIAL 状态渲染表格并给出完整性提示', () => {
      const wrapper = mount(ResultTable, {
        props: {
          block: table({
            crossSourceIntegrity: {
              reason: '来源报表暂不可用',
              state: 'PARTIAL',
            },
          }) as never,
        },
      });

      expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
        true,
      );
      expect(
        wrapper.find('[data-testid="ai-message-table-cross-source"]').text(),
      ).toContain('PARTIAL');
    });
  });

  describe('反向：授权拒绝时一个数字都不许渲染', () => {
    it('wITHHELD 不渲染表格、不渲染行、不渲染条数', () => {
      const wrapper = mount(ResultTable, {
        props: {
          block: table({
            crossSourceIntegrity: CROSS_SOURCE_WITHHELD,
            pageInfo: { page: 1, size: 20, total: 2 },
          }) as never,
        },
      });

      // 表格整体不渲染
      expect(wrapper.find('[data-testid="ai-message-table"]').exists()).toBe(
        false,
      );
      // 分页条数不渲染：行数本身就是信道，留着就能反推被禁来源的规模
      expect(
        wrapper.find('[data-testid="ai-message-table-page"]').exists(),
      ).toBe(false);
      // "没有可显示的数据行"也不渲染：那会让人以为是空结果而非被拒绝
      expect(
        wrapper.find('[data-testid="ai-message-table-empty"]').exists(),
      ).toBe(false);
      // 整段文本不含任何一个金额
      expect(wrapper.text()).not.toContain('100.00');
      expect(wrapper.text()).not.toContain('30.00');
      expect(wrapper.text()).not.toContain('C-001');
      expect(
        wrapper.find('[data-testid="ai-message-table-withheld"]').exists(),
      ).toBe(true);
    });

    it('wITHHELD 的提示必须说明"重试无用"，否则用户会反复重试', () => {
      expect(integrityNotice(CROSS_SOURCE_WITHHELD)).toContain('不是临时故障');
      expect(integrityNotice(CROSS_SOURCE_WITHHELD)).toContain(
        '重试不会改变结果',
      );
      expect(rendersNothing(CROSS_SOURCE_WITHHELD)).toBe(true);
    });

    it('提示文案不得回显被禁来源的名字或数量', () => {
      // 后端已在服务端拒绝，前端不重造被禁来源清单
      const notice = integrityNotice(CROSS_SOURCE_WITHHELD);
      expect(notice).not.toMatch(/payment|回款|来源\s*\d/);
    });

    it('pARTIAL 的长理由被截断，避免把长文本塞进图注', () => {
      const long = 'x'.repeat(200);
      const notice = integrityNotice({ reason: long, state: 'PARTIAL' });

      expect(notice).not.toContain(long);
      expect(notice.length).toBeLessThan(long.length);
    });
  });

  describe('解析：未知状态必须被拒绝而不是降级成完整', () => {
    it('未知 state 让整块降级为 unsupported（fail-closed）', () => {
      const parsed = toRenderableBlocks([
        table({ crossSourceIntegrity: { state: 'SUPER_VISIBLE' } }),
      ] as never);

      // 关键：不得渲染成一张"看起来完整"的表
      expect(parsed[0]?.kind).toBe('unsupported');
    });

    it('合法状态正常解析', () => {
      const parsed = toRenderableBlocks([
        table({ crossSourceIntegrity: { reason: '无权', state: 'WITHHELD' } }),
      ] as never);

      // 取出局部变量再做收窄：RenderableBlock 是判别联合，
      // 直接写 parsed[0].block 会因为重复下标访问丢掉收窄而报 TS2339
      const first = parsed[0];
      expect(first?.kind).toBe('supported');
      if (first?.kind === 'supported' && first.block.kind === 'table') {
        expect(first.block.crossSourceIntegrity).toEqual({
          reason: '无权',
          state: 'WITHHELD',
        });
      }
    });

    it('缺省（无跨源口径）不改变原有行为', () => {
      expect(integrityNotice(undefined)).toBe('');
      expect(rendersNothing(undefined)).toBe(false);
    });
  });
});
