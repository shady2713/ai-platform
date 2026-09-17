# ChartRenderer 与前端包验证报告（F04）

本记录是 [F04 建立前端包与图表集成验证](../ai-platform/tasks/F04.md) 的交付物：包结构、图表适配选型、
CSP/恶意标题/生命周期/包体实测结果与未验证项。原始日志与产物统计归档在 `.local-state/f04-frontend/`（Git 忽略）。

## 1. 结论摘要

| 决策 | 结果 |
|---|---|
| 图表唯一默认适配 | **@antv/g2 5.4.8**（morn 固定版本，见 `pnpm-workspace.yaml` catalog 注释） |
| GPT-Vis 候选 | **不采用**：`@antv/gpt-vis@1.0.1` 传递依赖 `@antv/g6`（图关系渲染）+ `@zumer/snapdom`（截图）+ `measury`，且 1.0.x 发布仅 13 天；平台已有自有 ChartSpec 契约，无需再引入 LLM 友好的第二套 spec |
| 自有契约 | `@vben/ai-contracts`：ChartSpec / Theme / ResultBlock，zod 校验，无厂商类型 |
| 渲染适配 | `@vben/ai-chat-ui` 的 `ChartRenderer`：ChartSpec → G2 实例，厂商类型不出组件 |
| 宿主 SDK | `@vben/ai-embed-sdk`：纯 TS，无 Vue 运行时依赖，按开放 API 契约调用 /runs |
| 独立应用 | `@vben/ai-chat`：最小可运行 Chat，只消费开放 API，不加载管理端配置 |

## 2. 包结构

```text
packages/ai-contracts/   ChartSpec / Theme / ResultBlock 契约与 zod 校验（纯 TS）
packages/ai-chat-ui/     AiChatPanel + ChartRenderer（Vue 组件，G2 适配唯一落点）
packages/ai-embed-sdk/   开放 API 客户端（受理/查询/取消，注入 fetch，无 Vue 依赖）
apps/ai-chat/            独立 Chat 构建入口（消费上述三包，独立 vite 配置）
```

依赖方向：app → chat-ui → contracts；app → embed-sdk → contracts。三个包均为源码导出（`exports` 指向 `src/`），
与仓库既有包（`@vben/constants`、`@vben/common-ui`）一致。

独立 Chat 刻意**不使用** `@vben/vite-config`：该共享配置注入管理端公开运行时配置
（`VITE_GLOB_API_URL`、`window._VBEN_ADMIN_PRO_APP_CONF_`），而架构约束要求独立 Chat 不加载后台路由、
管理权限与 ADMIN refresh 逻辑；本应用只读 `VITE_AI_API_BASE_URL` 访问开放 API。

## 3. 图表适配验证

### 3.1 生产构建体积（`pnpm -F @vben/ai-chat run build`）

| 产物 | 原始 | gzip |
|---|---|---|
| `vendor-antv-*.js`（图表库） | 1,305.32 kB | **387.21 kB** |
| `vendor-vue-*.js` | 62.62 kB | 24.92 kB |
| `index-*.js`（应用代码） | 6.24 kB | 3.12 kB |
| `index-*.css` | 0.31 kB | 0.21 kB |

图表库是包体主项；已在 vite 配置中按 `@antv` / vue 分包，便于记录与缓存。若后续要求压缩，
可评估按需引入（G2 运行时按 spec 加载）或改用更轻的渲染层；当前以"锁定版本 + 记录包体"为准。

### 3.2 公共 CDN 请求

产物静态扫描（`grep -rhoE "https?://..."`）：仅出现

| URL | 次数 | 性质 |
|---|---|---|
| `http://www.w3.org` | 5 | SVG/XML 命名空间常量，非网络请求 |
| `https://vuejs.org` | 1 | Vue 警告文案中的文档链接 |
| `https://ai.example.com` | 1 | 本应用配置的开放 API 基址（部署时替换） |

`index.html` 只引用本地 `/assets/*`；**无任何公共 CDN 脚本或样式引用**。

### 3.3 严格 CSP

- 产物中未出现 `eval(`；出现 **1 处 `new Function(`**，定位为 d3-dsv 的 DSV 行转换函数
  （`function $U(e){return new Function("d","return {"+...}`），由 G2 依赖链带入。
- 平台的 `ChartSpec` 只接受结构化数组（`categories: string[]`、`series[].data: number[]`），
  不提供 CSV/DSV 文本入口，因此该分支在平台数据路径上不可达；严格 CSP（无 `unsafe-eval`）
  下渲染不会触发它。
- **未验证**：浏览器内真实 CSP 报错采集需要真实浏览器，按任务卡约定留待 Q06 浏览器门禁；
  本卡只给出静态证据与数据路径论证。

### 3.4 恶意标题与注入面

- 标题与类目全部走 Vue 文本插值，不使用 `v-html`；组件测试断言：标题为
  `<img src=x onerror="...">` 时，DOM 中不出现 `img`/`script` 元素、不执行脚本、文本按原文展示。
- `ResultBlock` 为判别联合，未知 `kind` 在 zod 解析期拒绝，渲染器不会"尽力而为"渲染好奇数据。

### 3.5 生命周期（resize/destroy）

- `ChartRenderer` 对外只暴露 `resize()`/`destroy()`；`onBeforeUnmount` 与 spec 变更都会销毁旧实例
  （测试断言 `destroy` 调用次数与重建顺序），避免 canvas 与监听器泄漏。
- `resize()` 使用容器实际尺寸调用 G2 `changeSize`（测试以假实现断言入参为容器宽高）。

## 4. 门禁接线与拒绝演示

| 项 | 证据 |
|---|---|
| typecheck 契约 | `check-typecheck-contract.mjs`：37 个含 tsconfig 的包全部登记；新增四个包分别使用 `tsc --noEmit`（纯 TS）与 `vue-tsc --noEmit --skipLibCheck`（含 Vue） |
| 缺 typecheck 被拒绝 | 探针包（无 `typecheck` 脚本）→ 退出码 1，消息 `typecheck 应为 "tsc --noEmit"，当前为 undefined` |
| 错误命令被拒绝 | 探针包 `typecheck: "tsc"` → 退出码 1，消息 `当前为 "tsc"` |
| 依赖完整性 | `vsh check-dep`：新增包依赖与使用一致（曾拦下 app 中未使用的 `@vben/ai-contracts`，已移除） |
| 覆盖率范围 | `vitest.config.ts` 增加 `apps/ai-chat/src/**`；入口 `main.ts` 排除 |
| 生产构建 | `pnpm build`（turbo）已纳入 `@vben/ai-chat:build`；`run-frontend-build.mjs` 退出码 0 |

## 5. 测试与检查结果

| 命令 | 结果 |
|---|---|
| `pnpm check` | 退出码 0：循环依赖 0、依赖检查通过、显式 any 6/8、类型检查契约通过、cspell 0 问题 |
| `pnpm lint` | 退出码 0（prettier / eslint / stylelint 全部通过） |
| `pnpm test:coverage` | 292 个测试文件 / 1541 用例全部通过；语句覆盖率 89.86%（阈值 81/87.6/80.2） |
| `pnpm -F @vben/ai-chat run build` | 退出码 0，产物如 3.1 |
| `sh .harness/verify.sh lockfile` | 退出码 0（新依赖与锁文件一致） |
| `sh .harness/verify.sh dependencies` | 退出码 0（后端 SBOM、前端锁文件、Dockerfile 配置、应用镜像四段扫描 0 命中） |
| `sh .harness/verify.sh frontend` | 退出码 0（check + lint + 覆盖率 + 生产构建 + 单文件覆盖率棘轮） |

新增测试：ChartSpec/Theme/ResultBlock 校验（含恶意输入与越界值）、ChartRenderer（渲染桥接、标题转义、
销毁/重建、resize）、AiChatPanel（分块渲染、提交裁剪、禁用态）、SDK 客户端（幂等键长度、路径与头、
业务错误码→稳定错误、缺 data）、会话逻辑（未配置/成功/失败/并发进行中）。

## 6. 变更与范围说明

- 共享文件改动（均与本卡验收直接相关，已在交接记录标注）：`pnpm-workspace.yaml`（catalog 增加 `@antv/g2: 5.4.8`）、
  `pnpm-lock.yaml`、`vitest.config.ts`（覆盖率范围）、`cspell.json`（词表增加 `antv`，与既有 `antd/antdv` 同例）。
- 未新增数据库迁移；未改动后端。

## 7. 未验证项与后续条件

1. **真实浏览器渲染与 CSP 报错采集**：待 Q06 建立浏览器门禁后补齐（本卡不声称已通过浏览器验收）。
2. 图表包体优化（按需引入/更轻渲染层）：需要时另开任务，升级 G2 或调整引入方式都必须重跑本报告的 3.1–3.5。
3. GPT-Vis 重新评估条件：若后续需要关系图/网络图能力（G6 场景）或官方发布进入稳定维护期，重跑候选对比。
