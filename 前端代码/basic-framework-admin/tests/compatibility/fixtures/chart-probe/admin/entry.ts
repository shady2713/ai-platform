/**
 * Q11 管理端页面浏览器验收探针（真实 Chromium）。
 *
 * <p>与 AT-065/AT-070 同一个 `global-setup` 现场构建、同一个静态服务、同一个
 * `page.html` 挂载点，只多一个 `#admin-page` 容器与本入口暴露的 `__q11` 桥接面。
 * 手法与 Y06 扩面时给 `entry.ts` 加 `mountResultTable` 完全一致（追加式）。
 *
 * <p>什么是真的、什么是桥接（读用例前请先看这张表）：
 *
 * | 部件 | 真/桥 | 理由 |
 * |---|---|---|
 * | 页面 `.vue`（`views/ai` 下的 `index.vue`） | **真** | 入口显隐、插槽名、prop 名都在这里 |
 * | 页面 `data.ts`（列/表单词表、权限码） | **真** | 文案与列逐位断言的对象 |
 * | `TableAction`（`components/table-action`） | **真** | `auth` 可见性判定在 `actions.ts` 里 |
 * | `Page`、`zod`、i18n 词表 | **真** | 无应用外壳依赖 / 真实词表 |
 * | 列表数据 | 桥 | 冻结夹具行经**页面自己的**查询函数下发（只换 HTTP） |
 * | 网格/表单/弹窗控件、HTTP | 桥 | 需要应用外壳、路由与后端（Q11 §4 排除真实凭据联调） |
 *
 * <p>不复制任何渲染或判定逻辑：可见性、禁用、文案逐字来自生产代码。
 */
import type { App, Component } from 'vue';

import { createApp, h } from 'vue';

import lucideIcons from 'q11:lucide-icons';
import { ElConfigProvider } from 'q11:element-plus';
import elementPlusZhCn from 'q11:element-plus-lang-zh-cn';
import { addCollection } from '../../../../../packages/@core/base/icons/src/index';

import AuthorizationPage from '../../../../../apps/web-ele/src/views/ai/authorization/index.vue';
import ConnectorPage from '../../../../../apps/web-ele/src/views/ai/connector/index.vue';
import DatasetPage from '../../../../../apps/web-ele/src/views/ai/dataset/index.vue';
import ModelEndpointPage from '../../../../../apps/web-ele/src/views/ai/model-endpoint/index.vue';
import ReportPage from '../../../../../apps/web-ele/src/views/ai/report/index.vue';
import SemanticPage from '../../../../../apps/web-ele/src/views/ai/semantic/index.vue';

import { setReportFailures } from './api-report-bridge';
import { getModalApis } from './common-ui-bridge';
import { setupI18n } from './locales-bridge';
import {
  getState,
  recordError,
  resetState,
  setAccessCodes,
  setRows,
} from './state';

/** 离线注册 lucide 全量图标：真实 `TableAction` 用字符串图标（`lucide:ellipsis-vertical`），
 * 不注册就会在运行期外发 `api.iconify.design`（Q06 §4.b 已登记）。探针不发外部请求。 */
addCollection(lucideIcons, { prefix: 'lucide' });

const PAGES: Record<string, Component> = {
  authorization: AuthorizationPage,
  connector: ConnectorPage,
  dataset: DatasetPage,
  'model-endpoint': ModelEndpointPage,
  report: ReportPage,
  semantic: SemanticPage,
};

const apps = new Map<string, App>();

function target(selector: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(selector);
  if (!element) {
    throw new Error(`探针容器不存在：${selector}`);
  }
  return element;
}

function unmount(key: string): void {
  const app = apps.get(key);
  if (app) {
    app.unmount();
    apps.delete(key);
  }
}

interface MountOptions {
  codes: string[];
  rows: Array<Record<string, unknown>>;
  /** 报表页专用：注入票据/刷新失败文案（其他页面忽略）。 */
  reportFailures?: { refresh?: string; ticket?: string };
}

async function mountPage(name: string, options: MountOptions): Promise<void> {
  const page = PAGES[name];
  if (!page) {
    throw new Error(
      `探针未注册该页面：${name}（已注册：${Object.keys(PAGES).join(', ')}）`,
    );
  }
  unmount(name);
  resetState();
  setAccessCodes(options.codes);
  setRows(options.rows ?? []);
  setReportFailures(options.reportFailures ?? {});

  const app = createApp({
    render: () =>
      // 中文语言包与生产一致（生产在 `#/locales` + `ElConfigProvider` 上挂），
      // 否则 ElPopconfirm 确认按钮会是默认英文 `Yes/No`，浮层文案断言就失真了。
      h(ElConfigProvider, { locale: elementPlusZhCn }, () => h(page)),
  });
  app.config.errorHandler = (error) => {
    recordError(`vue:${String(error)}`);
  };
  // $t 必须在挂载前就绪：页面模板里就有 $t(...) 调用。
  await setupI18n(app);
  app.mount(target('#admin-page'));
  apps.set(name, app);
}

Object.assign(globalThis, {
  __q11: {
    errors: () => [...getState().errors],
    /** 打开第 index 个弹窗（注册顺序），并灌入数据。 */
    openModal: (index: number, data?: unknown) => {
      const api = getModalApis()[index];
      if (!api) {
        throw new Error(
          `没有第 ${index} 个弹窗（已注册 ${getModalApis().length} 个）`,
        );
      }
      if (data !== undefined) {
        api.setData(data);
      }
      api.open();
    },
    snapshot: () => {
      const current = getState();
      return {
        accessCodes: [...current.accessCodes],
        apiCalls: current.apiCalls.map((call) => ({
          args: call.args,
          name: call.name,
        })),
        errors: [...current.errors],
        feedback: current.feedback.map((item) => ({ ...item })),
        gridLoads: current.gridLoads,
      };
    },
    mountPage: (name: string, options: MountOptions) =>
      mountPage(name, options),
    unmountAll: () => {
      for (const key of [...apps.keys()]) {
        unmount(key);
      }
    },
  },
});
