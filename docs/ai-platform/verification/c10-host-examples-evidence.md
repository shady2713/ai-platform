# C10 交付第三方宿主示例与兼容验收 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C10](../tasks/C10.md) |
| 状态 | DONE（示例、产物与基线夹具；**真实浏览器验收与录像见"未验证项"**） |
| 需求 | FR-08、FR-14、FR-15、FR-34 |
| 依赖 | C09（集成与主题页，证据 `c09-*`）、O08（开放平台与调试入口，已有证据）、R07（报表页，证据 `r07-*`） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `examples/ai-host-html`、`examples/ai-host-vue`（新增）、`docs/integrations`、`packages/ai-embed-sdk`（版本化产物 + N-1 夹具）、`pnpm-workspace.yaml`、`pnpm-lock.yaml` |

## 1. 变更文件清单

### 纯 HTML 宿主（`examples/ai-host-html`）

- `server.mjs`：最小换票后端 + 静态服务——`POST /your-backend/ai-ticket`（用服务端配置的客户端凭据换票）、
  `GET /sdk/<版本化产物>`（长缓存、只提供固定命名文件、拒绝目录穿越）、`GET /` 与 `/host.js`。
  **三条红线**：未配置凭据直接 503；只允许配置的宿主 Origin（其他 403 且不回通配 CORS）；平台基址来自服务端配置。
- `host-model.mjs`：宿主侧纯逻辑（登记路由、组装 SDK 选项、票据获取、切用户动作）。
- `host.js`：浏览器入口，加载**版本化 SDK 产物**并把 UI 接到生命周期与事件上。
- `public/index.html`：宿主页面（打开/关闭、三种形态、主题、上下文、切用户、销毁）。
- `server.test.ts`：后端红线与静态资源约束的单测（4 例）。

### Vue 宿主（`examples/ai-host-vue`）

- `src/host.ts`：纯逻辑（登记路由、SDK 选项、主题预设、上下文仓库、切用户）；
- `src/App.vue` / `src/main.ts` / `index.html` / `vite.config.mts` / `tsconfig.json`：可构建可运行的 Vue 宿主外壳；
- `src/host.test.ts`：路由登记与拒绝、上下文"只作用下一次运行"、主题/切用户行为（3 例）。

### SDK 产物与 N-1 基线（`packages/ai-embed-sdk`）

- `vite.config.mts` + `package.json` 的 `build` 脚本：产出 `dist/ai-embed-sdk-<version>.js`（自托管、固定版本路径；本次实测 100.84 kB / gzip 23.50 kB）。
- `fixtures/n-1/baseline.json`：**首发基线夹具**（sdkVersion、protocolVersion、消息类型白名单、完整握手序列、产物 sha256）。
- `src/__tests__/n-1-baseline.test.ts`：每次门禁重放基线（解析每条消息 + 当前实现走到 INITIALIZED，3 例）。

### 文档与工作区

- `docs/integrations/ai-host-integration.md`：接入指南 + 换票红线 + 兼容口径 + 本轮验收记录 + 未验证项。
- `pnpm-workspace.yaml`：登记 `examples/*`；`pnpm-lock.yaml`：新增 `examples/*` 与 SDK 的构建依赖（vite）。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 纯 HTML 与 Vue 宿主 + 最小后端换票 | 两个示例 + `server.mjs` | `server.test.ts`（换票红线）、`host.test.ts`（Vue 宿主行为）、示例可构建（Vue 产物 129.97 kB） |
| 2. 不同 Origin 完成真实流程 | 宿主端口 5180 / 平台 48080 不同源；示例覆盖打开/关闭/形态/主题/上下文/切用户/过期（`TOKEN_REQUIRED` 由 SDK 单飞续票） | 端口级测试 + 指南中的复现命令；**真实浏览器流程未验证** |
| 3. 版本化 SDK 产物与 N-1 基线夹具 | `dist/ai-embed-sdk-5.6.0.js` + `fixtures/n-1/baseline.json` | `n-1-baseline.test.ts`（白名单不缩水、协议版本一致、握手可重放） |
| 4. 示例登记到 workspace/typecheck/测试；后端不得成为匿名换票代理 | `pnpm-workspace.yaml` 登记；Vue 示例有 `typecheck` 脚本并进入 38/38 任务；`server.mjs` 的 503/403 约束 | `pnpm run check:type`（38/38）、`server.test.ts` |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| 可运行宿主示例 | 通过（可构建可启动）：Vue 示例构建产物生成；HTML 示例由 `server.mjs` 提供（未配置凭据时 503 明确失败） | 构建输出 + `server.test.ts` |
| SDK 包（版本化产物） | 通过：`ai-embed-sdk-<version>.js` 由包内 `build` 产出，示例后端按固定命名提供 | 构建输出、`server.test.ts` |
| 无公网 CDN | 通过：两个示例只引用自托管产物 | 代码与指南；`server.test.ts`（只提供本地产物） |
| 构建产物可加载且没有示例 secret | 通过（结构）：示例凭据来自环境变量，代码与文档里没有真实凭据；`server.test.ts` 断言响应不含 `appSecret` | `server.test.ts` |
| 禁第三方 Cookie | 结构上不依赖 Cookie（票据经请求头传递）；**浏览器验收未完成** | 见未验证项 |
| AT-057 N-1 兼容 | **首发基线验证**：冻结首发候选协议行为并每次门禁重放；真实双产物联调属 Q06 | `n-1-baseline.test.ts` |

## 3. 关键约束落地

- **示例不是生产换票代理**：没有凭据即 503、只允许配置 Origin、平台基址服务端决定、响应不含凭据。
- **不重复实现协议**：两个宿主示例都只用 SDK 的端口与校验器；宿主只决定"登记什么路由、何时换票、是否跳转"。
- **产物自托管、版本化**：SDK 产物文件名含版本，示例后端按固定命名提供且长缓存；不使用 CDN 与浮动路径。
- **首发阶段如何做 N-1**：按 FR-34 冻结首发候选的协议行为作为兼容夹具，并明确标注"首发基线验证"。

## 4. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `pnpm -F @vben/ai-embed-sdk run build`（内部 `vite build`） | 0 | `dist/ai-embed-sdk-5.6.0.js` 100.84 kB（gzip 23.50 kB），单文件含依赖 |
| `pnpm -F @ai-platform-examples/host-vue run build` | 0 | `dist/index.html` + `assets/index-*.js` 129.97 kB（gzip 42.18 kB） |
| `pnpm exec vitest run --dom examples packages/ai-embed-sdk/src/__tests__` | 0 | `Test Files 4 passed`，**19 例通过**（HTML 宿主 4 + Vue 宿主 3 + N-1 基线 3 + 既有 SDK） |
| `pnpm run check:type` | 0 | **38/38 任务**（含新登记的 `@ai-platform-examples/host-vue`） |
| `pnpm run check` / `pnpm run lint` | 0 / 0 | cspell 1025 文件 0 问题；prettier/eslint/stylelint 全部通过 |
| 门禁链（contracts / lockfile / frontend / 棘轮） | 见第 6 节 | 本卡未改后端与迁移，但改了工作区与锁文件 |

## 5. 新增/变化的对外契约与上游差异

- **工作区**：新增两个示例包（`@ai-platform-examples/host-html` / `-vue`）；`packages/ai-embed-sdk` 新增 `build` 脚本与 `vite` 开发依赖（锁文件已同步）。
- **产物契约**：`dist/ai-embed-sdk-<version>.js`（自托管、固定命名）；示例后端只按该命名提供。
- **上游差异**：
  1. SDK 的构建依赖（vite）在本卡声明——原包的 `exports` 指向源码，第三方宿主无法直接使用；产物是 C10 的必须产出。
  2. 示例的凭据来自环境变量（`AI_APP_CODE`/`AI_APP_SECRET`），**仓库里没有任何真实凭据**；示例后端明确不是生产换票代理。
  3. `examples/*` 未纳入前端覆盖率白名单（vitest 的 coverage include 只覆盖 `apps/**` 与 `packages/**`），
     因此示例代码不产生棘轮义务；其测试仍会被 `pnpm test:coverage` 执行（作为回归）。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 全部契约脚本通过（本卡未改后端、迁移与台账，快照仍为 `through V80`） |
| `sh .harness/verify.sh lockfile` | 0 | `pnpm install --frozen-lockfile` 通过：新增的两个示例包与 SDK 的构建依赖都已正确登记在锁文件 |
| `sh .harness/verify.sh frontend` | 0（复跑） | 首轮 exit 1 的原因是**示例文件的 eslint**（`examples/**/package.json` 键序、`host.js` 的导入顺序与 `dataset` 偏好）；修好后复跑通过：`check`（cspell 1025 文件 0 问题）+ `lint` + `test:coverage`（**362 个测试文件全过**）+ 生产构建（`Production JavaScript: 230 files passed no-undef validation`，比上轮多 2 个：SDK 与 Vue 宿主产物进入校验）+ 前端棘轮 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 无新增前端文件（示例与 SDK 产物不在覆盖率白名单内，锁文件与工作区文件不参与），既有基线全部保持 |

> 说明：本卡是纯前端/示例/文档变更，未改后端与迁移，因此按既有口径跑 contracts + lockfile + frontend；
> 示例代码不进入覆盖率白名单（vitest 的 coverage include 只覆盖 `apps/**` 与 `packages/**`），
> 但示例的测试会被 `pnpm test:coverage` 执行（作为回归），其类型由新登记的 `typecheck` 脚本覆盖（38/38）。

## 7. 顺带修复的依赖缺口（真实暴露）

1. **新工作区成员需要真实安装**：只跑 `pnpm install --lockfile-only` 不会建立 `node_modules` 链接，
   Vue 示例的 `@vben/ai-embed-sdk` 解析失败；执行一次 `pnpm install` 后正常（已记入交接记录）。
2. **示例的 TypeScript 要真正进入 typecheck**：新包默认不在 `turbo run typecheck` 范围内，
   补 `typecheck` 脚本 + `tsconfig.json` 后才被覆盖（并立刻暴露了两处真实类型错误）。
3. **严格类型下的 Record 索引**：`Record<string, …>` 在 `noUncheckedIndexedAccess` 下取值可能为 undefined，
   主题预设改为字面量键的 Record 后类型收敛。
4. **样式规则**：示例 SFC 的 `<style scoped>` 需要满足仓库的属性顺序与空行约定，用 `stylelint --fix` 一次修好。

## 8. 未验证项与已知边界

1. **真实浏览器验收与"接入验收录像"未完成**：AT-050/056/067 的跨源行为、第三方 Cookie 禁用、窄屏与键盘走查
   需要 Q06 建立的真实浏览器门禁；本卡交付示例、复现命令与端口级测试，**不声称**已录制真实浏览器验收。
2. **真实平台联调未在 CI 执行**：示例需要本地平台（48080）与真实应用凭据才能完成一次真实对话；
   本卡未在门禁里启动平台。
3. **N 与 N-1 双产物联调**：首发无历史产物，只完成基线夹具重放（FR-34 的"首发基线验证"口径）。
4. **示例后端的能力边界**：示例只演示换票；生产需要会话鉴权、按主体申请票据、限流与审计（指南已写明）。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。
