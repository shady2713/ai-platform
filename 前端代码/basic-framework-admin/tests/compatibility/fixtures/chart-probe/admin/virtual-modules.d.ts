/**
 * Q11 探针的虚拟模块类型声明。
 *
 * <p>`q11:*` 三个 id 由 `fixtures/chart-probe/global-setup.mjs` 在构建期解析到
 * 真实文件（lucide 图标 JSON、element-plus ESM 入口、element-plus 中文语言包）。
 * 之所以走虚拟 id 而不直接 alias 包名，见 global-setup 里的说明：
 * 改写 `element-plus` 会连产品代码（`table-action.vue`）的解析一起动。
 *
 * <p>本文件只提供类型，不参与运行。
 */
declare module 'q11:lucide-icons' {
  /** `@iconify/json` 的 lucide 集合（`addCollection` 的入参形状）。 */
  const collection: {
    icons: Record<string, { body: string; width?: number }>;
    prefix: string;
  };
  export default collection;
}

declare module 'q11:element-plus' {
  import type { Component } from 'vue';

  /** element-plus 的 `ElConfigProvider`：生产用它挂中文语言包，探针同法。 */
  export const ElConfigProvider: Component;
}

declare module 'q11:element-plus-lang-zh-cn' {
  /** element-plus 简体中文语言包。 */
  const locale: { el: Record<string, unknown>; name: string };
  export default locale;
}
