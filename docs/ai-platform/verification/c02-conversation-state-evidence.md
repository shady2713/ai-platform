# C02 实现会话状态与消息组件 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C02](../tasks/C02.md) |
| 状态 | DONE（本文件记录的命令均已实际执行；浏览器端复连/多标签验收见"未验证项"） |
| 需求 | FR-12, FR-13 |
| 依赖 | C01（共享开放客户端，已有证据）、O01（会话与消息，已有证据） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |

## 1. 变更文件清单

### 会话状态与组件（`packages/ai-chat-ui/src/conversation`）

- `state.ts`：**纯状态机**——五态（等待/执行/确认/失败/完成）、服务端状态映射（未知状态不猜，保持执行中）、
  seq 去重、代次隔离（`switchGeneration`）、错误键去重、重试复用幂等键；受理在途也按"执行中"处理。
- `use-conversation.ts`：会话逻辑 composable——列表 CRUD、发送/取消/重试、代次隔离；
  `ConversationApi`/`ConversationRunApi` 由宿主注入（可单测、可换宿主），`createIdempotencyKey` 生成长度合规的幂等键。
- `ConversationPanel.vue`：界面——会话列表（新建/重命名/删除/选择）、消息列表（文本/错误/其它结果块）、
  阶段徽标、发送/取消/重试；挂载即加载列表；全部文本插值（无 `v-html`）。
- `README.md`：模块说明与行为约定。

### 独立 Chat 应用（`apps/ai-chat/src`）

- `conversation-api.ts`：`ConversationApi` 端口的应用端实现（`/ai/conversation/page|create|rename|delete`，
  票据只在请求头，CommonResult 信封错误与共享协议层同口径，错误类型复用 SDK 导出的 `AiChatApiError`）。
- `App.vue`：外壳改用 `ConversationPanel`（装配 `runApi` 与 `conversationApi`）；未配置基址时给出明确状态。
- `__tests__/conversation-api.test.ts`（3 例）、`__tests__/app.test.ts`（更新为会话面板外壳）。
- `packages/ai-embed-sdk/src/types.ts`：`CreateRunRequest.conversationId` 由字符串改为**数值编号**
  （与开放 API 契约一致；会话端口与 SDK 客户端的类型因此可互换）。

### 测试

- `packages/ai-chat-ui/src/conversation/__tests__/state.test.ts`（6）
- `packages/ai-chat-ui/src/conversation/__tests__/use-conversation.test.ts`（7）
- `packages/ai-chat-ui/src/conversation/__tests__/conversation-panel.test.ts`（4）
- `apps/ai-chat/src/__tests__/conversation-api.test.ts`（3）

**本卡不新增迁移、不改后端**（纯前端会话逻辑与组件）。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 会话列表/新建/重命名/删除/发送/取消/重试 | `useConversation` 暴露全部动作；`ConversationPanel` 提供界面入口（挂载即加载列表） | `use-conversation.test.ts`（列表 CRUD、删除当前会话清空界面）；`conversation-panel.test.ts`（新建/重命名/删除/发送/取消按钮） |
| 2. 状态机区分等待/执行/确认/失败/完成 | `state.ts` 五态 + 服务端状态映射；确认态由 `WAITING_CONFIRMATION` 事件进入；失败态由取消/错误进入；终态由 `SUCCEEDED/FAILED/CANCELLED` 进入 | `state.test.ts`（五态映射、终态后可再次发送、确认中拒绝重复发送） |
| 3. 会话与用户 generation 隔离，历史资源失权有提示 | `switchGeneration()` 在切用户/切会话/删除当前会话时换代并清空；旧代次的受理结果、事件与错误一律丢弃；服务端稳定错误码（含失权类）由错误块原样展示 | `use-conversation.test.ts`（切用户后旧响应丢弃）；`state.test.ts`（旧代次回调全丢弃）；`conversation-panel.test.ts`（错误块展示） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-014 SSE 断线重连 | 通过（客户端侧由 C01 覆盖；本卡消费 `streamRunEvents` 并把 `reason=closed` 视为"可重连"而不改变阶段） | `use-conversation.test.ts`（事件流推进阶段）；C01 证据覆盖重连/窗口过期 |
| AT-016 取消与晚到完成并发 | 通过 | `state.test.ts`（取消进入终态、重复取消不生效）；`use-conversation.test.ts`（取消调用服务端 cancel，晚到完成不回到执行中） |
| AT-053 宿主切用户 | 通过 | `use-conversation.test.ts`：切用户后清空界面并丢弃晚到的旧受理结果（不出现"已受理运行 run_old"） |
| 晚到旧用户消息丢弃 | 通过 | 同上（受理结果 + 事件 + 错误三条回调路径都在状态机里按代次过滤） |
| 连续点击不重复 run | 通过 | `state.test.ts` + `use-conversation.test.ts`：受理在途与执行中第二次发送被忽略，`createRun` 只调用一次 |
| 错误只提示一次 | 通过 | `state.test.ts`（同错误键只提示一次、换键才提示）；`use-conversation.test.ts`（重试后同一错误不再追加错误块） |

## 3. 关键约束落地

- **状态机可单测**：并发语义（重复发送、取消终态、代次隔离、错误去重）全部在纯函数式状态机里，
  组件只做展示与转发——这是"取消后晚到完成不覆盖""切用户丢弃旧响应"能被逐条证明的前提。
- **端口注入**：本包不依赖具体请求库；宿主实现 `ConversationApi`（应用端实现见 `apps/ai-chat/src/conversation-api.ts`），
  测试用假端口即可覆盖全部并发分支。
- **票据不落 URL/storage**：会话端口实现只在请求头带票据（`Authorization: Bearer`），请求体不含令牌。
- **不新增依赖、不放宽门禁**：仅复用工作区已有包（`@vben/ai-chat-ui`、`@vben/ai-embed-sdk`）；
  未改门禁脚本、阈值与锁文件；既有文件覆盖率基线不降（新增 3 例覆盖会话端口实现）。
- **无 TODO/假数据/空实现**：所有分支都有实现与用例；未实现的能力（分享/发布等）不放入界面。

## 4. 实际执行的命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（前端命令在 `前端代码/basic-framework-admin`）。

| 命令 | 结果 |
|---|---|
| `pnpm exec vitest run --dom packages/ai-chat-ui/src/conversation apps/ai-chat/src` | exit 0；`Tests 34 passed`（会话 17 + 应用 12 + 既有 5） |
| `pnpm run check:type` | exit 0（37/37 任务） |
| `pnpm exec eslint <本卡新增/修改文件>` | exit 0 |
| `pnpm test:coverage` | exit 0（`Test Files 340 passed`，全局阈值通过） |
| `sh .harness/verify.sh contracts` | exit 0 |
| `sh .harness/verify.sh frontend` | exit 0：`check`（vue-tsc 37/37）+ `lint` + `test:coverage`（`Test Files 341 passed`）+ 生产构建 + `Production JavaScript: 228 files passed no-undef validation`；首次运行仅因 4 个新文件"尚未登记单文件覆盖率基线"失败（新增文件的预期状态），登记后通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | exit 0：登记 4 个新文件基线（`state.ts`、`use-conversation.ts`、`ConversationPanel.vue`、`conversation-api.ts`）；`all 单文件基线通过` |

## 5. 新增/变化的对外契约

- **前端 API**：`@vben/ai-chat-ui` 新增导出 `ConversationPanel`、`useConversation`、`createConversationMachine`、
  `phaseOfRunStatus`、`createIdempotencyKey` 与会话相关类型。
- **上游契约差异**：SDK 的 `CreateRunRequest.conversationId` 由 `string` 改为 `number`
  （与开放 API 契约的数值编号一致）；`apps/ai-chat` 外壳改用会话面板（原最小发送流程 `use-chat.ts` 保留，
  其用例仍在门禁内）。
- **字段/权限/迁移**：无。

## 6. 未验证项与已知边界

1. **浏览器端验收未完成**：真实 EventSource 断线重连、多标签切换用户、窄带慢消费者的浏览器行为
   由 Q06/G5 承接（任务卡明确浏览器测试命令在 Q06 建立前不存在）；本卡证据为状态机、composable 与组件级测试。
2. **确认态的人工确认入口**：`WAITING_CONFIRMATION` 的确认/拒绝按钮属于工具确认动作链（D09 的 action 接口，
   `apps/ai-chat` 侧接入由 C03/C09 与 Q 系列推进）；本卡只把确认态纳入状态机与徽标。
3. **会话接口实现只在独立 Chat 应用**：后台管理端与第三方宿主的会话端口实现分别由 C09/C06 交付
   （端口契约已在 `packages/ai-chat-ui/src/conversation` 冻结）。
4. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy DB 镜像不可达仍失败（见前序卡记录）。
