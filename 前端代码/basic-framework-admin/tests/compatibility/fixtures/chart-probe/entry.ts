/**
 * AT-065 真实浏览器渲染探针（Q08 前端切片）。
 *
 * <p>为什么需要它：happy-dom 没有 canvas，G2 只能走降级路径；"升级后仍能真实绘图"必须用
 * 真实 Chromium + 真实 canvas 断言。本入口挂载的是**真实组件**（只读引用，不改一行源码）：
 *  - `AiChart`（R02 适配层）与 `ChartRenderer`（消息层用的适配组件）；
 *  - `AiReportView`（报表层，四类块 + 图表块）。
 * 驱动样例来自 `../../../fixtures/at-065/render-samples`（与 vitest 用例同一份冻结样例）。
 *
 * <p>页面只暴露"挂载/卸载/快照"，不做任何数据伪造：canvas 是否真的画出像素由 Playwright
 * 侧读取 `getImageData` 判定。
 */
import type { App } from 'vue';

import { createApp, h } from 'vue';

import AiChart from '../../../../packages/ai-chat-ui/src/chart/AiChart.vue';
import ChartRenderer from '../../../../packages/ai-chat-ui/src/components/ChartRenderer.vue';
import AiReportView from '../../../../packages/ai-chat-ui/src/report/AiReportView.vue';
import {
  FROZEN_CHART_SPEC,
  FROZEN_LINE_SPEC,
  REPORT_DATA_SAMPLE,
  REPORT_SPEC_SAMPLE,
} from '../at-065/render-samples';

const apps = new Map<string, App>();
const errors: string[] = [];

function target(selector: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(selector);
  if (!element) {
    throw new Error(`探针容器不存在：${selector}`);
  }
  return element;
}

function mountApp(
  key: string,
  selector: string,
  render: () => ReturnType<typeof h>,
): void {
  unmount(key);
  const app = createApp({ render });
  app.config.errorHandler = (error) => {
    errors.push(String(error));
  };
  app.mount(target(selector));
  apps.set(key, app);
}

function unmount(key: string): void {
  const app = apps.get(key);
  if (app) {
    app.unmount();
    apps.delete(key);
  }
}

function unmountAll(): void {
  for (const key of [...apps.keys()]) {
    unmount(key);
  }
}

globalThis.addEventListener('error', (event) => {
  errors.push(`window.error: ${event.message}`);
});

Object.assign(globalThis, {
  __q08: {
    errors: () => [...errors],
    mountAll: () => {
      mountApp('ai-chart', '#chart-ai-chart', () =>
        h(AiChart, { spec: FROZEN_CHART_SPEC, theme: 'light' }),
      );
      mountApp('chartrenderer', '#chart-renderer', () =>
        h(ChartRenderer, { spec: FROZEN_CHART_SPEC }),
      );
      mountApp('line', '#chart-line', () =>
        h(AiChart, { spec: FROZEN_LINE_SPEC }),
      );
      mountApp('report', '#report', () =>
        h(AiReportView, {
          data: REPORT_DATA_SAMPLE,
          spec: REPORT_SPEC_SAMPLE,
          theme: 'light',
        }),
      );
    },
    unmountAll,
  },
});
