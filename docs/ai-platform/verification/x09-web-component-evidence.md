# X09 组件级 Chat 集成包与宿主适配 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [X09](../tasks/X09.md) |
| 需求 | FR-30/FR-31（Chat 集成：iframe 与组件两条路径）；AT-051/052/053/054/055/056/057/059/067 |
| 依赖 | C10（组件级集成包前置，已交付）、Q10（已交付） |
| 工作副本（唯一可写） | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 基线 commit | `2ca7bab`（X03） |
| 前端工作目录 | `/home/ctyun/桌面/zhongtai/ai-platform/前端代码/basic-framework-admin` |
| 未验证 | 真实浏览器验收（组件路径的跨源/网关流程、内存观察、网络级无 CDN 断言——`tests/` 属 Q06 路径，不在本卡范围）；与真实平台（48080 + 真实凭据）的端到端联调 |

## 1. 交付范围

新包 `@vben/ai-web-component`：把既有 ChatUI（`ConversationPanel` + `useConversation` 状态机）挂进 Shadow DOM，
对外暴露与 iframe 嵌出**同一套宿主契约**（身份/票据、主题 token、路由注册、安全事件桥）。

```text
<ai-chat-component app-code="…" instance-id="…">   自定义元素（组件路径）
宿主属性/回调：getAccessToken（与 iframe 同签名的票据回调）、theme、routes
宿主事件：ai-ready / ai-error / ai-navigate-request / ai-rejected / ai-report-created / ai-destroyed
```

两处刻意**不重复实现**：状态机与渲染复用 `packages/ai-chat-ui`；宿主侧桥接复用 `ai-embed-sdk` 的 `HostBridge`
（组件路径用页内直连传输，嵌入侧用同一契约解析器 + 每实例身份令牌）。

## 2. 变更清单（要点）

| 位置 | 内容 |
|---|---|
| `packages/ai-web-component/**`（新包） | `src/element/ai-chat-element.ts`（自定义元素与 Shadow DOM 样式隔离）、`src/config.ts`（宿主配置与安全校验）、`src/component/controller.ts`（生命周期/事件/票据）、`src/protocol/local-port.ts`（页内嵌出侧协议，含实例级来源校验）、`src/runtime/ai-ports.ts`（宿主端口）、`src/surface/*`（挂载、主题）、`scripts/{verify-artifact,minify-artifact}.mjs`（包体预算 + dist 无公共 CDN 扫描） |
| `examples/ai-host-html/**` | 组件页演示（`public/component.html`、`component.js`、`component-model.mjs`）+ 同源网关（`/your-backend/app-api/*`，固定前缀与目标、方法与头白名单、无 cookie、SSE 透传）；顺带修掉 C10 示例里 `server.mjs` 静态文件路径错误的真实缺陷 |
| `examples/ai-host-vue/**` | 同一页面并列演示 iframe 与组件两条路径（`src/component-host.ts` + `App.vue`），构建产物随 C10 的既有约定更新 |
| 文档 | `docs/integrations/ai-web-component-integration.md`（兼容矩阵 + iframe→组件迁移步骤 + 已知差异），`docs/integrations/ai-host-integration.md` 加交叉引用与构建步骤 |
| 锁文件 | `pnpm-lock.yaml`（新包加入 workspace） |

## 3. 验收映射

| 验收项 | 判据 | 证据（真实命令见 §4） |
|---|---|---|
| 宿主样式不破坏交互 | 注入对抗性宿主 CSS（`* { display:none!important; visibility:hidden }`）后，Shadow DOM 内交互仍可用，组件样式自带显式重置 | `element.test.ts` |
| 两个组件实例身份隔离 | 各自票据只出现在自己的请求头；事件按实例归属；销毁 A 不影响 B | `instance-isolation.test.ts`（5 例） |
| 旧 iframe 路径兼容 | 既有 `createChatMount` 全握手（open→HELLO→READY→AUTH/INIT→导航接受/拒绝→destroy）不回归；冻结的 N-1 基线夹具可回放；同一页面两条路径共存 | `iframe-compat.test.ts`（3 例） |
| 包体预算 | 目录实测：入口 212,309 B（gzip 68,933）、样式 1,252 B、图表懒加载块 1,311,015 B（gzip 388,045）；预算在 `scripts/verify-artifact.mjs` 内声明并通过 | `verify:artifact` 退出 0 |
| 无公共 CDN（本包范围） | 源码与 dist 双向扫描（CDN 主机、`@import`/`url()`、绝对地址），并与 F04 的 `public-cdn-registry.json` 对齐 | `no-public-cdn.test.ts` + `verify:artifact`（**只证明本包**，全站结论仍属 Q06/AT-067） |

## 4. 验证执行

| 命令（工作目录 `前端代码/basic-framework-admin`） | 退出码 | 结果 |
|---|---|---|
| `pnpm --filter @vben/ai-web-component exec vue-tsc --noEmit --skipLibCheck` | 0 | 类型检查通过 |
| `pnpm --filter @ai-platform-examples/host-vue exec vue-tsc --noEmit --skipLibCheck` | 0 | 宿主示例类型检查通过 |
| `node scripts/check-typecheck-contract.mjs` | 0 | 39/39 包登记（新包使用 `vue-tsc`，与契约一致） |
| `pnpm exec vitest run --dom packages/ai-web-component/src examples --coverage --coverage.include='packages/ai-web-component/src/**'` | 0 | 15 文件 / 105 例通过；包内行覆盖 95.95%（新文件均 ≥80%） |
| `pnpm -F @vben/ai-web-component run build` + `run verify:artifact` | 0 | dist 重建、包体预算与无 CDN 扫描通过 |
| `pnpm -F @ai-platform-examples/host-vue run build` | 0 | 134.19 kB / gzip 43.43 kB |
| `pnpm install --frozen-lockfile --ignore-scripts` | 0 | 锁文件一致 |
| `pnpm exec eslint|prettier|stylelint|cspell`（新包与两个示例） | 0 | 全部干净 |
| `pnpm run check:circular` / `check:dep` / `node scripts/check-explicit-any.mjs` | 0 | 无循环依赖；显式 any 棘轮 6 ≤ 8（本卡未增加） |
| 四道门禁 | 见 §5 | |

## 5. 门禁结果（为 X04/X09 共同复跑）

| 门禁 | 退出码 | 关键结果 |
|---|---|---|
| `contracts` | 0 | 源码质量、台账、权限目录、门禁接线契约全绿（与 X04 同批复跑） |
| `backend` | 0（同批） | 本卡未改后端；同批后端门禁全绿 |
| `integration` | 0（同批） | 本卡未改后端；同批集成门禁全绿 |
| `frontend` | 0（登记基线后） | `pnpm check` + `lint` + `test:coverage` + 生产构建 + 两个示例构建全绿：401 个测试文件 / 2272 例（本卡新增 105 例）、工作区行覆盖 91.82%；新包 10 个源文件登记进单文件覆盖率基线后棘轮通过 |

## 6. 已知差异与未验证项

- **组件路径的主题校验更严**：主题 token 走白名单校验（`fontFamily` 必须自托管），与 iframe 路径的差异在兼容矩阵里写明，属刻意收紧而非回退。
- **组件路径需要同源 API 访问**：请求由宿主页面发出，跨源调用需要平台侧 CORS（后端不在本卡范围），因此示例提供同源网关并推荐该部署方式。
- **仓库自带的 `apps/ai-chat/scripts/check-public-cdn.mjs` 扫描清单未包含新包**（该文件不在本卡允许路径内）：新包自带脚本与测试覆盖自身；把新包并入全站扫描清单留给后续卡。
- **真实浏览器验收与真实平台联调未做**：`tests/`（Q06 Playwright 套件）不在本卡允许路径，组件路径尚无浏览器级验收；示例的网关/票据链有单测但未对真实平台跑通。
