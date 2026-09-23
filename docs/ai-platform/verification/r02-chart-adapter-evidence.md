# R02 AntV 图表适配组件证据（2026-09-23）

本记录是 [R02 实现AntV图表适配组件](../tasks/R02.md) 的验收证据。
依赖 [F04](../tasks/F04.md)（前端框架）、[F07](../tasks/F07.md)（ChartSpec 契约）、[R01](../tasks/R01.md)（ReportSpec 校验与绑定）
均已有证据文档。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 厂商无关行转换 | `packages/ai-chat-ui/src/chart/specToRows.ts`：类目缺失补空、值缺失保留 `null`（不补 0）、金额保留十进制原始文本 |
| 降级判定与表格模型 | `chart/fallback.ts`：空类目/无数值/饼图多系列/饼图类目过多/类目过多 → 表格 + 可读原因 |
| 厂商选项（唯一出口） | `chart/chartOptions.ts`：column/line/pie 三种类型 + 主题令牌 |
| 主题与显示 | `chart/theme.ts`：深浅色令牌、长标签截断（保留首尾）、大数千分位 |
| 生命周期 | `chart/useChartInstance.ts`：动态 import 懒加载、ResizeObserver、主题/规格变化重渲染、destroy 幂等 |
| 组件 | `chart/AiChart.vue`：`spec` + `theme` 入参，渲染图表或降级表格，只暴露 `resize`/`destroy` |
| 说明 | `chart/README.md`：模块表、用法样例、行为约定与未验证项 |
| 测试 | `chart/__tests__/chart-adapter.test.ts` 6 例 |

对外方法：组件 props `{ spec: ChartSpec; theme?: string }`，暴露 `resize()` / `destroy()`；
纯函数 `specToRows`、`fallbackReason`、`toTableModel`、`toVendorOptions`、`themeTokens`、`truncateLabel`、`formatLargeNumber`。

本卡为前端包内新增（无表、无迁移、无端点），四处台账无需改动。

## 2. 与卡片逐步实施的对应

1. **按 P0 选定组件实现 column/line/pie 和表格降级**：渲染库沿用 F04 台账已锁定的 `@antv/g2`
   （`@vben/ai-chat-ui` 的既有依赖）；`bar` 映射为柱状（interval）、`line` 为折线、`pie` 为饼图（theta 坐标系，
   只取第一个系列）；不满足渲染条件时**降级为表格**并给出稳定原因码与中文说明。
2. **ChartSpec 转换不泄漏 vendor 格式**：`specToRows`/`fallback`/`theme` 全部厂商无关；
   G2 选项只在 `chartOptions.ts` 构造，调用处按 `unknown` 传入，对外 props/emits/暴露方法与 README 样例里
   没有任何厂商类型；单测断言选项对象的键集合固定且不含厂商专有字段。
3. **懒加载、resize、theme、destroy**：`@antv/g2` 用动态 import；`ResizeObserver` 跟随容器尺寸并在卸载时断开；
   `theme` 变化重新渲染（令牌来自 `theme.ts`）；`destroy()` 幂等，卸载/规格变化/主题变化都先销毁旧实例，
   懒加载期间卸载不会创建实例。

## 3. 关键约束与安全语义

- **无脚本执行**：标题、类目、系列名一律文本插值（不用 `v-html`）；ChartSpec 契约本身不含脚本/样式字段。
- **缺失值不伪装成 0**：`null`/空字符串在行数据里保留为 `null`（折线断点、柱状留空），
  降级判定还会在"全无数值"时直接改用表格。
- **降级必须说明原因**：界面展示降级原因文案（空类目/无数值/饼图多系列/类目过多），
  用户不会把表格误当成"图表渲染失败"。
- **金额不失真**：显示层用数值（千分位、两位小数），原始值文本（十进制字符串）随行数据保留，
  表格直接展示原始文本。
- **实例不泄漏**：宿主拿不到厂商实例（只暴露 `resize`/`destroy`），因此不存在"漏销毁"的路径；
  懒加载失败降级为表格而不是白屏。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-044（空/null/长标签/金额） | 缺值不补 0、长标签截断保留首尾、大数千分位、金额原始文本进表格 | `把 ChartSpec 转成行数据…`、`空/null/长标签/大数都不溢出…`、`降级时渲染表格并说明原因…` |
| AT-054（深浅色与窄屏） | 主题令牌驱动颜色/字号，主题变化重渲染；容器尺寸由 ResizeObserver 跟随 | `渲染时把厂商选项限制在适配层…`、`主题变化重新渲染，窄屏 resize 跟随容器` |
| AT-055（destroy 后重复 mount） | destroy 幂等、卸载后重复挂载不残留实例、resize 可用 | `destroy 幂等且重复挂载不残留实例（AT-055）` |
| 无脚本执行 | 文本插值渲染 + 契约无脚本字段 | `AiChart.vue` 模板（无 `v-html`）+ F07 契约 |
| 无残留实例 | 卸载/规格/主题变化先销毁；懒加载期间卸载不建实例 | `useChartInstance.ts` + 上述 AT-055 用例 |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `pnpm vitest run packages/ai-chat-ui/src/chart` | 0 | **6 例通过** |
| `pnpm --filter @vben/ai-chat-ui run typecheck` | 0 | 类型检查通过（厂商类型未扩散） |

## 6. 顺带修复的依赖缺口（真实暴露）

1. **首次渲染时机**：最初在 `<script setup>` 里直接调用渲染，此时容器 ref 还不存在（模板未渲染），
   渲染被静默跳过。改为 `onMounted` 后渲染，组件级用例因此能观察到厂商调用。
2. **厂商选项的类型边界**：G2 的 `options()` 参数类型与适配层的通用选项对象不兼容；
   改为按 `unknown` 传入（不把厂商类型带进适配层签名）。
3. **测试数据与契约一致**：用例里最初用 `null` 表达缺失值，但 ChartSpec 契约的取值是
   "数值或十进制字符串"，缺失以空字符串表达；测试数据已按契约修正。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh frontend` | 1 → 1 → **0** | 中间两次失败分别是 eslint 风格规则（perfectionist/unicorn）与 prettier 格式、以及尾部棘轮未登记新文件；逐项修复（`eslint --fix`、格式化、`--update`）后复跑**全绿**（依赖/类型/拼写、lint、覆盖率、生产构建与棘轮） |
| `sh .harness/verify.sh contracts` / `backend` | 0 / 0 | 本卡未改后端与契约，两条门禁保持通过（R01 提交时已验证） |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记本卡新增 6 个前端文件；无下调、无删除 |

## 8. 覆盖率

本卡新增前端文件在完整覆盖率数据下由 `node scripts/check-coverage-ratchet.mjs --update` 登记单文件基线；
只新增条目或上调既有值。

## 9. 未验证项

1. **真实浏览器渲染样例（AT-044/054/055 的浏览器部分）**：本卡交付组件与组件级测试；
   跨源、真实渲染、窄屏与"destroy 后无监听器/图表泄漏"的内存观察由 Q06/G5 用真实浏览器补齐，
   本卡不声称已完成。
2. **与报表渲染链路接线**：本卡提供适配组件，报表页（R06/R07）尚未接入；
   `@vben/ai-chat-ui` 的包出口（`src/index.ts`）不在本卡允许路径内，未改动（接入由后续卡片完成）。
3. **主题令牌与后端主题契约的一致性**：令牌取值与 `theme-tokens` 契约语义一致（十六进制颜色），
   但"后端主题修订号驱动前端令牌"的联动未验证（属 F07/主题卡范围）。
4. **大数据量性能**：类目上限（渲染 60、饼图 12）按可读性设定，未做十万级数据压测。
