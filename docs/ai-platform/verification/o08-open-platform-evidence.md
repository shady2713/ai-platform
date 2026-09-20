# O08 开放平台目录与在线调试证据（2026-09-20）

本记录是 [O08 交付开放平台目录和在线调试](../tasks/O08.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 开放平台页面 | `apps/web-ele/src/views/ai/open-platform/index.vue`：按能力域浏览开放接口目录（方法/路径/说明/形态/鉴权与归属）、详情展示限额与示例、复制示例、在线调试面板 |
| 目录与帮助数据 | `views/ai/open-platform/data.ts`：目录过滤与统计、示例凭据校验（`exampleLeaksCredential`）、复制文本、调试表单与调试须知（`DEBUG_NOTICE`） |
| 调试通道 | `api/ai/open-platform/index.ts`：目录数据（仅已发布接口）、`issueDebugTicket`（应用客户端凭据换短期票据）、`callDebugEndpoint`（带票据调用开放端点） |
| 菜单与权限 | 迁移 `V65__ai_open_platform_menu.sql`：菜单 4040「AI 开放平台」（`ai:open-platform:query`，仅用于菜单可见性）+ 快照同步；`permission-catalog.json` 的 `unusedCatalog` 登记该菜单级权限 |
| 组件与 API 测试 | `api/ai/open-platform/index.test.ts`(3)、`views/ai/open-platform/data.test.ts`(5)、`views/ai/open-platform/index.test.ts`(5)，共 13 例 |

## 2. 与卡片逐步实施的对应

1. **显示服务/API 目录、版本、scope、限额和示例**：目录按能力域（认证/运行/任务/会话/文件）分组，
   每条给出方法、路径、说明、形态（同步 / 异步受理 / SSE 事件流）、鉴权与归属说明与限额清单；
   详情面板展示请求/响应示例与可能的状态码说明，并提供"复制示例"。
   目录来源是 O07 冻结的开放 API 规范（`docs/integrations/open-api/ai-open-api.json`），
   **只登记已发布接口**：未登记 id 在目录里查不到，调试面板也不会提供入口（测试断言）。
2. **在线调试使用短期受限测试票据，不能回显 secret**：调试走**应用端通道**（`baseURL=/app-api`、
   `withCredentials=false`，不携带管理端令牌），用操作者输入的应用标识与秘密换取短期票据，
   再用 `Authorization: Bearer <ticket>` 调用所选开放端点；票据只驻留页面内存（刷新即失效），
   应用秘密只在换票请求体里出现——响应区域与页面文本都不回显秘密与令牌（测试断言页面文本
   既不含 `aiapp_secret` 也不含 `aitkt_once`）。调试权限与真实身份一致：票据主体没有的权限，
   调试同样拿不到（后端按票据主体判定归属与授权）。
3. **展示 SSE、异步和错误示例**：目录里 SSE 端点（`run-events`）展示事件帧与心跳注释样例，
   并说明"先鉴权再开流、心跳不推进 seq、重放窗口过期请读运行进度与快照"；
   异步受理端点（`run-accept`）说明"受理后通过事件流或进度查询取结果，不要重复提交"；
   每个端点列出可能的状态码与业务错误码说明（401/403/409/429/502/503/504）。

## 3. 关键约束与安全语义

- **调试与真实身份权限一致**：调试不引入任何"超管旁路"——它复用应用端换票与主体归属判定，
  因此票据主体的授权就是调试的授权上限。
- **复制示例无真实 token**：`exampleLeaksCredential` 拦截疑似真实凭据（`aitkt_`/`aiapp_`/`sk-`/
  `Bearer <长串>`），目录示例一律使用占位符（`<APP_SECRET>` / `<TICKET>`）；
  复制前再次校验，命中即阻止复制并提示（测试逐条断言目录内所有示例均通过）。
- **未发布接口不出现**：目录只含已发布端点；调试目标必须来自目录（查不到即拒绝）。
- **凭据不落盘**：应用秘密与票据都不写入 localStorage/状态库，也不进入 URL（票据只放在请求头）。
- **失败不伪造成功**：调试失败只提示稳定结论，不渲染结果块，也不回显上游正文。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `pnpm exec vitest run --dom apps/web-ele/src/api/ai/open-platform apps/web-ele/src/views/ai/open-platform` | 0 | 13 例通过（API 契约 3、目录数据 5、页面 5） |
| `pnpm check` | 0 | 依赖/循环/显式 any/类型/拼写检查通过（cspell 词表补入 `aitkt`/`aiapp` 两个协议前缀） |
| `pnpm lint` | 0 | prettier + eslint 通过 |
| `sh .harness/verify.sh frontend` | 1 → 0 | 类型、lint、覆盖率测试与构建通过；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 登记后复验通过 |
| `sh .harness/verify.sh integration` | 0 | 44 个 IT 类 / 127 例全绿（V65 菜单迁移与快照一致） |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 3 个前端文件（最低 89.37%），无基线下调 |

## 5. 顺带修复的依赖缺口

本卡未发现需要顺带修复的既有缺口。目录数据以 O07 的规范为唯一来源，避免"页面自己写一份目录"；
调试通道刻意不复用 `#/api/request` 的 admin 客户端（避免把管理端令牌带到应用端接口上），
而是用独立的 `RequestClient({ baseURL: '/app-api', withCredentials: false })`。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录（含新增保留登记）、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 1 → 0 | 类型/lint/覆盖率/构建通过；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `sh .harness/verify.sh integration` | 0 | 全量 IT 与后端棘轮通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 前端新增 3 项登记，无下调、无删除 |

## 7. 覆盖率

| 文件 | 行覆盖率 |
|---|---|
| `api/ai/open-platform/index.ts` | 100% |
| `views/ai/open-platform/data.ts` | 95.48% |
| `views/ai/open-platform/index.vue` | 89.37% |

## 8. 未验证项

1. **浏览器端到端**：本卡按组件测试基线交付；真实浏览器中的目录浏览、复制与调试调用
   （含剪贴板权限差异）在 Q06 的浏览器验收中补齐。
2. **真实应用凭据调试**：调试需要可用应用的客户端凭据与已发布服务；本机与 CI 未装配模型客户端，
   因此端到端只覆盖到"换票/调用失败时不伪造成功、不回显凭据"。
3. **后续能力域的目录**：知识库、数据集、报表、主题等端点由各自卡片实现后按 O07 的扩展流程补入目录，
   Q10 在完整发布目录上复验。
