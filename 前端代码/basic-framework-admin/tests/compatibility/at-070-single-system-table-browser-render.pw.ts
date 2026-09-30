/**
 * AT-070 / AT-049 / AT-057 / AT-063（Y06 前端切片）：**单系统**结果表格在真实 Chromium
 * 中按升级前的样子渲染。
 *
 * <p>为什么浏览器项必须真跑：Y05 为跨源授权完整性改过共享渲染层
 * （`ResultTable.vue` / `blocks.ts`），而这些文件同时服务旧的单系统表格块。
 * happy-dom 下的单测只能证明"函数返回了什么"，证明不了"浏览器里画出了什么"——
 * 而用户真正会遇到的症状（整段数字消失、条数不见了）正是在浏览器里出现的。
 *
 * <p>探针挂载的是**真实组件**（只读引用，不改一行产品源码）：载荷经生产公开入口
 * `parseMessageBlock` 解析后交给真实 `ResultTable` 渲染。
 *
 * <p>断言口径沿用 Q06/Q08 约定：只做结构断言（元素、文本、行数），不做视觉回归
 * （ADR 0046 决策 2）。金额断言的是**精确文本**（'450.00'），不是数值近似。
 */
import { expect, test } from '@playwright/test';

/** 旧单系统表格块：V2 之前后端只会产出这些字段（不带 crossSourceIntegrity）。 */
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

async function openProbe(page: import('@playwright/test').Page) {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(String(error)));
  await page.goto('http://127.0.0.1:5399/');
  await page.waitForFunction(() => {
    const bridge = (
      globalThis as unknown as { __q08?: { mountResultTable?: unknown } }
    ).__q08;
    return typeof bridge?.mountResultTable === 'function';
  });
  return pageErrors;
}

test.describe('AT-070 单系统结果表格的真实浏览器渲染回归（V2 上线后无回退）', () => {
  test('旧单系统表格块在真实 Chromium 中照旧渲染出数字、行数与分页条数', async ({
    page,
  }) => {
    const pageErrors = await openProbe(page);
    await page.evaluate((payload) => {
      (
        globalThis as unknown as {
          __q08: { mountResultTable: (input: unknown) => void };
        }
      ).__q08.mountResultTable(payload);
    }, LEGACY_TABLE_PAYLOAD);

    const table = page.locator(
      '#result-table [data-testid="ai-message-table"]',
    );
    await expect(table).toBeVisible();

    // D11 黄金集金额逐位一致，且是**文本**渲染（没被本地化成 450）
    await expect(table.locator('td[data-field="net_amount"]')).toHaveText([
      '450.00',
      '290.00',
    ]);
    await expect(table.locator('td[data-field="customer_name"]')).toHaveText([
      'bob',
      'alice',
    ]);
    await expect(table.locator('thead th').nth(1)).toHaveText('净额（CNY）');
    await expect(table.locator('tbody tr')).toHaveCount(2);

    // 分页条数是升级前就有的信息，V2 上线后不得消失（条数本身不泄漏，但也不能凭空少）
    const pageCaption = page.locator(
      '#result-table [data-testid="ai-message-table-page"]',
    );
    await expect(pageCaption).toHaveText('第 1 页 · 每页 20 条 · 共 2 条');

    // 没有跨源授权字段 → 不得出现任何跨源提示
    await expect(
      page.locator('#result-table [data-testid="ai-message-table-withheld"]'),
    ).toHaveCount(0);
    await expect(
      page.locator(
        '#result-table [data-testid="ai-message-table-cross-source"]',
      ),
    ).toHaveCount(0);

    expect(pageErrors, '单系统渲染不得产生未捕获错误').toEqual([]);
  });

  test('（对照）显式 WITHHELD 时浏览器里确实一个数字都不出', async ({
    page,
  }) => {
    await openProbe(page);
    await page.evaluate(
      (payload) => {
        (
          globalThis as unknown as {
            __q08: { mountResultTable: (input: unknown) => void };
          }
        ).__q08.mountResultTable(payload);
      },
      {
        ...LEGACY_TABLE_PAYLOAD,
        crossSourceIntegrity: {
          state: 'WITHHELD',
          reason: '部分来源不在你的授权范围内，该结果未出具。',
        },
      },
    );

    await expect(
      page.locator('#result-table [data-testid="ai-message-table-withheld"]'),
    ).toBeVisible();
    // 表格与分页条数都不出：上一条"照旧渲染"不是因为组件没渲染，而是这里才该被清空
    await expect(
      page.locator('#result-table [data-testid="ai-message-table"]'),
    ).toHaveCount(0);
    await expect(
      page.locator('#result-table [data-testid="ai-message-table-page"]'),
    ).toHaveCount(0);
    await expect(page.locator('#result-table tbody tr')).toHaveCount(0);
  });
});
