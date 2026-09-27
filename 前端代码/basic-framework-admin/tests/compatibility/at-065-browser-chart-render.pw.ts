import type { Page } from '@playwright/test';

/**
 * AT-065（Q08 前端切片）：真实 Chromium 下的图表/报表渲染回归 —— 冻结候选 AntV/G2 5.4.8。
 *
 * <p>为什么要有这一条：vitest 的 happy-dom 没有 canvas，只能证明"降级路径正确"；
 * "升级候选后仍能真实绘图"必须用真实 canvas 读像素来证明。本用例挂载真实组件
 * （`AiChart` / 消息层的 `ChartRenderer` / 报表层 `AiReportView`），驱动样例与 vitest 用例
 * 同源（`tests/compatibility/fixtures/at-065/render-samples.ts`，其图表样例已与 F07 冻结样例逐字段对齐）。
 *
 * <p>断言口径：
 *  - canvas 存在且尺寸合理；`getImageData` 统计非透明像素 > 0（真画出来了，不是空白画布）；
 *  - 报表层四类块与元信息文本存在，图表块内也有真实 canvas；
 *  - 卸载后 canvas 全部移除（无"僵尸画布"）；
 *  - 页面零未捕获错误（`window.error` + 组件 errorHandler 都归集）。
 *
 * <p>本用例不访问后端、不请求外部域：页面与探针产物都由本地 127.0.0.1:5399 提供。
 */
import { expect, test } from '@playwright/test';

interface CanvasProbe {
  canvases: {
    height: number;
    paintedPixels: number;
    transparent: boolean;
    width: number;
  }[];
  errors: string[];
  found: boolean;
}

/** 读取容器内所有 canvas 的尺寸与非透明像素数（真绘制判据）。 */
async function inspectCanvases(
  page: Page,
  selector: string,
): Promise<CanvasProbe> {
  return page.evaluate((containerSelector) => {
    const container = document.querySelector(containerSelector);
    const canvasList = container
      ? [...container.querySelectorAll('canvas')]
      : [];
    const canvases = canvasList.map((element) => {
      const context = element.getContext('2d');
      const { width, height } = element;
      let paintedPixels = 0;
      if (context && width > 0 && height > 0) {
        const data = context.getImageData(0, 0, width, height).data;
        for (let index = 3; index < data.length; index += 4) {
          if (data[index] !== 0) {
            paintedPixels += 1;
          }
        }
      }
      return { height, paintedPixels, transparent: paintedPixels === 0, width };
    });
    const bridge = (
      globalThis as unknown as {
        __q08?: { errors: () => string[] };
      }
    ).__q08;
    return {
      canvases,
      errors: bridge ? bridge.errors() : [],
      found: canvasList.length > 0,
    };
  }, selector);
}

test.describe('AT-065 冻结候选（AntV/G2 5.4.8）真实浏览器渲染回归', () => {
  test('图表适配层与报表层在真实 Chromium 中绘制出 canvas 像素，且无未捕获错误', async ({
    page,
  }) => {
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error)));

    await page.goto('http://127.0.0.1:5399/');
    await page.waitForFunction(() => {
      const bridge = (
        globalThis as unknown as { __q08?: { mountAll?: () => void } }
      ).__q08;
      return typeof bridge?.mountAll === 'function';
    });
    await page.evaluate(() => {
      (
        globalThis as unknown as { __q08: { mountAll: () => void } }
      ).__q08.mountAll();
    });

    // 三个图表面板 + 报表里的图表块都要画出真实像素
    for (const selector of [
      '#chart-ai-chart',
      '#chart-renderer',
      '#chart-line',
    ]) {
      await expect
        .poll(
          async () => {
            const probe = await inspectCanvases(page, selector);
            return probe.canvases[0]?.paintedPixels ?? 0;
          },
          { message: `${selector} 应绘制出非透明像素（G2 真实渲染）` },
        )
        .toBeGreaterThan(1000);
      const probe = await inspectCanvases(page, selector);
      const canvas = probe.canvases[0];
      if (!canvas) {
        throw new Error(`${selector} 没有 canvas`);
      }
      expect(canvas.width).toBeGreaterThan(100);
      expect(canvas.height).toBeGreaterThan(50);
    }

    // 报表层：四类块与元信息按契约渲染
    await expect(page.locator('[data-testid="ai-report-title"]')).toHaveText(
      '2026年8月华东客户净销售额',
    );
    await expect(page.locator('[data-testid="ai-report-metric"]')).toHaveText(
      '740 CNY',
    );
    await expect(
      page.locator('[data-testid="ai-report-completeness"]'),
    ).toHaveText('完整数据');
    await expect(page.locator('[data-testid="ai-report-table"]')).toContainText(
      'alice',
    );
    await expect(
      page.locator('[data-testid="ai-report-table"]'),
    ).not.toContainText('不可见列');
    await expect(
      page.locator('[data-testid="ai-report-chart"] canvas'),
    ).toHaveCount(1);
    await expect
      .poll(async () => {
        const probe = await inspectCanvases(
          page,
          '[data-block-id="sales_chart"]',
        );
        return probe.canvases[0]?.paintedPixels ?? 0;
      })
      .toBeGreaterThan(1000);

    const probe = await inspectCanvases(page, '#targets');
    expect(probe.errors).toStrictEqual([]);
    expect(pageErrors).toStrictEqual([]);

    // 卸载后不残留画布（与 AT-055 同一语义：destroy 彻底）
    await page.evaluate(() => {
      (
        globalThis as unknown as { __q08: { unmountAll: () => void } }
      ).__q08.unmountAll();
    });
    await expect(page.locator('canvas')).toHaveCount(0);
    await expect(page.locator('[data-testid="ai-report"]')).toHaveCount(0);
  });
});
