/**
 * `#/locales` 的探针桥接：`$t` 是**真实**的全局 i18n 翻译函数。
 *
 * <p>`#/locales` 自身只是把 `@vben/locales` 的 `i18n.global.t` 再导出一次
 * （`apps/web-ele/src/locales/index.ts:98`），并额外挂 element-plus 语言包与 dayjs。
 * 后两者与本卡的文本断言无关，故此处直接复用真实 `@vben/locales` 实例：
 * 文案逐位断言验的是**真实词表**，不是探针里的替身文案。
 *
 * <p>词表来源与生产一致，分两段：
 *  - 核心词表（`packages/locales/src/langs`）由 `@vben/locales` 自己的 glob 装载，
 *    `ui.actionTitle.create`（"新增{0}"）在这里；
 *  - 应用词表（`apps/web-ele/src/locales/langs`）由本桥接用同样的 glob 方式合并，
 *    `page.action.more`（"更多"，`TableAction` 的下拉触发按钮文案）在这里。
 *
 * <p><b>glob 路径的 `..` 层数必须数清楚</b>：本文件在
 * `tests/compatibility/fixtures/chart-probe/admin/`，上溯 <b>5</b> 级才到
 * `basic-framework-admin/`（同目录其他 import 也是 5 级）。曾误写成 6 级，
 * glob 匹配到 0 个文件且**不报错**——于是核心词表照常加载（按钮文案"新增授权"正常），
 * 只有应用词表缺失（`page.action.more` 渲染成裸 key `page.action.more`），
 * 症状看起来像"产品词表坏了"，实为装置缺陷。`assertAppLocaleModules` 就是为此设的哨兵：
 * glob 空了立刻抛错，不许静默降级。
 */
import type { LocaleMessageMap } from '../../../../../packages/locales/src/typing';

import {
  $t,
  i18n,
  setupI18n as coreSetupI18n,
} from '../../../../../packages/locales/src/index';

export { $t, i18n };

/** 与生产 `apps/web-ele/src/locales/index.ts:22` 同法：把应用语言包收成按语言分组的加载器。 */
const appLocaleModules = import.meta.glob(
  '../../../../../apps/web-ele/src/locales/langs/**/*.json',
);

/** 哨兵：glob 写错时 `import.meta.glob` 静默返回空对象，必须在这里变成硬失败。 */
function assertAppLocaleModules(): void {
  const paths = Object.keys(appLocaleModules);
  if (paths.length === 0) {
    throw new Error(
      '探针未匹配到任何应用语言包：`../../../../../apps/web-ele/src/locales/langs/**/*.json` ' +
        '的 `..` 层数不对（从 admin/ 上溯 5 级到 basic-framework-admin/）。' +
        '继续下去会让 $t 渲染成裸 key，把装置缺陷误报成产品词表缺陷。',
    );
  }
  for (const lang of ['zh-CN', 'en-US']) {
    if (!paths.some((path) => path.includes(`/langs/${lang}/`))) {
      throw new Error(
        `探针未匹配到 ${lang} 语言包，实际匹配：${paths.join(', ')}`,
      );
    }
  }
}

async function loadAppMessages(lang: string): Promise<LocaleMessageMap> {
  assertAppLocaleModules();
  const messages: LocaleMessageMap = {};
  for (const [path, load] of Object.entries(appLocaleModules)) {
    const matched = path.match(/langs\/([^/]+)\/(.*)\.json$/);
    if (!matched || matched[1] !== lang) {
      continue;
    }
    const module = (await load()) as { default?: LocaleMessageMap };
    if (module.default) {
      messages[matched[2]] = module.default;
    }
  }
  return messages;
}

/** 与生产同签名；只固定 `zh-CN`（本卡所有文案断言都按中文词表逐位比对）。 */
export async function setupI18n(app: Parameters<typeof coreSetupI18n>[0]) {
  await coreSetupI18n(app, {
    defaultLocale: 'zh-CN',
    loadMessages: (lang: string) => loadAppMessages(lang),
  });
}
