/**
 * AT-039 / AT-060 的界面口径（真实浏览器 + 真实渲染组件）：部分结果与未知必须**显式标注**。
 *
 * 做法：夹具页（`fixtures/pages/q07-probe.html`）挂载真实产品组件——`blocks.ts` 的
 * `parseMessageBlock`（真实契约校验）+ `ResultTable.vue`（`MessageBlockView` 对表格块的同一实现），
 * 输入是一个"分页被截断"的表格块：
 *  - `completeness: PARTIAL/UNKNOWN` 必须在界面上出现标注（含"结果可能不完整"），
 *    不得显示成"完整"；
 *  - `completeness: COMPLETE` 不产生标注（不误报）；
 *  - 空单元格显示 `—`（不补 0），原始金额文本原样保留（AT-060"未知不写 0"的同一口径）；
 *  - 分页信息（页码/总数）如实展示，让"截断"可解释。
 *
 * 未覆盖：管理端用量页的 UNKNOWN/ESTIMATED 来源标签（Q03 组件级已通过）需要登录态与后端，
 * 浏览器级仍为未验证项；本用例只判"部分/未知结果块"的界面口径。
 */
import type { Page } from '@playwright/test';

import { expect, test } from '@playwright/test';

import {
  Q07_PROBE_PAGE_PATH,
  startQ07Api,
} from '../support/q07-resilience-api.mjs';

/** 探针页暴露的接口（页面上下文）。 */
interface ProbeResult {
  caption: null | string;
  cells: string[];
  emptyNote: null | string;
  headers: string[];
  page: null | string;
  rowCount: number;
  supported: boolean;
}

interface ProbeWindow {
  __q07Probe: {
    mountTable: (block: Record<string, unknown>) => ProbeResult;
  };
}

function tableBlock(completeness: null | string): Record<string, unknown> {
  return {
    columns: [
      { field: 'region', label: '区域' },
      { field: 'amount', label: '金额', unit: '元' },
    ],
    completeness: completeness ?? undefined,
    kind: 'table',
    pageInfo: { page: 1, size: 2, total: 45 },
    rows: [
      { amount: '450.00', region: '华东' },
      { amount: null, region: '华南' },
    ],
  };
}

async function mountTable(
  page: Page,
  block: Record<string, unknown>,
): Promise<ProbeResult> {
  return page.evaluate(
    (input) =>
      (globalThis as unknown as ProbeWindow).__q07Probe.mountTable(input),
    block,
  );
}

test.describe('AT-039/AT-060 部分结果与未知的界面口径', () => {
  test('PARTIAL/UNKNOWN 有标注、COMPLETE 不误报、空值不补 0', async ({
    page,
  }) => {
    const api = await startQ07Api();
    try {
      await page.goto(`${api.origin}${Q07_PROBE_PAGE_PATH}`);
      await expect(page.locator('#probe-status')).toHaveText('q07-probe-ready');

      // PARTIAL（分页被截断的典型场景）：必须标注，且不得显示为"完整"
      const partial = await mountTable(page, tableBlock('PARTIAL'));
      expect(partial.supported).toBe(true);
      expect(partial.caption).toBe('数据完整性：PARTIAL（结果可能不完整）');
      expect(partial.rowCount).toBe(2);
      // 分页信息如实展示（第 1 页 / 每页 2 条 / 共 45 条），让截断可解释
      expect(partial.page).toBe('第 1 页 · 每页 2 条 · 共 45 条');

      // 空值显示 —（不补 0），原始金额文本原样保留
      expect(partial.cells).toEqual(['华东', '450.00', '华南', '—']);
      expect(partial.headers).toEqual(['区域', '金额（元）']);

      // UNKNOWN：同样必须标注
      const unknown = await mountTable(page, tableBlock('UNKNOWN'));
      expect(unknown.caption).toBe('数据完整性：UNKNOWN（结果可能不完整）');

      // COMPLETE：不出现标注（不误报"不完整"）
      const complete = await mountTable(page, tableBlock('COMPLETE'));
      expect(complete.caption).toBeNull();
      expect(await page.locator('body').innerText()).not.toContain(
        '结果可能不完整',
      );

      // 未声明完整性：同样不得凭空标注"完整"
      const undeclared = await mountTable(page, tableBlock(null));
      expect(undeclared.caption).toBeNull();
      expect(await page.locator('body').innerText()).not.toContain(
        '数据完整性：COMPLETE',
      );
    } finally {
      await api.close();
    }
  });
});
