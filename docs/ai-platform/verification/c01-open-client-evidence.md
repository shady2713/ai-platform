# C01 实现开放API前端客户端 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C01](../tasks/C01.md) |
| 状态 | DONE（本文件记录的命令均已实际执行；浏览器端复连验收见"未验证项"） |
| 需求 | FR-08, FR-10, FR-12 |
| 依赖 | O07（开放 API 契约，已有证据）、F04（前端工作区与请求规范，已有证据） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |

## 1. 变更文件清单

### 共享协议层（`packages/ai-contracts/src/client`，两个消费方共用）

- `protocol.ts`：CommonResult 信封解析（`parseCommonResult`：HTTP 非 2xx / `code!=0` / 缺 `data` 都是稳定错误）、
  稳定错误 `AiOpenApiError`（`status` + `code`，调用方按码分支不解析文案）、seq 去重器 `createSeqTracker`
  （只接受严格递增，重复/乱序丢弃，`lastSeq()` 用于重连）。
- `sse.ts`：SSE 帧解析 `createSseFrameParser` / `createSseByteParser`：帧以空行结束、未结束部分留在缓冲
  （**事件截断不丢帧**）、多行 `data:` 拼接、`id`/`event` 保留、注释行（心跳）只记入 `comments`；
  字节解析用 `TextDecoder({stream:true})`，**UTF-8 字符跨块不乱码**。
- `src/index.ts`：导出上述函数与类型。

### 共享开放客户端（`packages/ai-chat-ui/src/client`）

- `index.ts`：`createOpenApiClient({ baseUrl, accessToken, exchangeTicket, fetchImpl })`：
  `acceptRun`（带 `Idempotency-Key`，`reused` 表示命中幂等）、`getRun`、`cancelRun`（乐观锁版本）、`pageRuns`、
  `streamRunEvents`（fetch + SSE，返回 `{lastSeq, reason}`；`reason=closed` 由调用方按 `afterSeq` 重连；
  重放窗口过期 `1003004009` → 读取快照）。
- `README.md`：模块说明、用法与行为约定。
- `__tests__/client.test.ts`（7 例）。

### 宿主 SDK（`packages/ai-embed-sdk/src`）

- `client.ts`：SDK 客户端改为在**同一份协议层**之上实现，并把端点对齐真实契约
  （`/ai/run/accept`、`/ai/run/get?id=`、`/ai/run/cancel`、`/ai/run/events?runId=&afterSeq=`），
  新增 `streamRunEvents`（去重/重连/窗口过期读快照）。
- `types.ts`：`RunAccepted`/`RunSnapshot` 从占位形状对齐开放 API 契约（`reused`/`runKey`/`releaseId`…）。
- `__tests__/client.test.ts`（6 例，按真实端点重写）。

### 导出

- `packages/ai-chat-ui/src/index.ts`、`packages/ai-embed-sdk/src/index.ts`。

**本卡不新增迁移、不改后端**（纯前端客户端与协议层）。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. Bearer 内存票据、CommonResult/真实 HTTP 错误、fetch SSE 解析 | 令牌只由宿主 `accessToken()` 提供并只写 `Authorization` 头；`parseCommonResult` 区分 HTTP 失败/业务失败/缺 data；SSE 用 fetch + 自研解析器（`EventSource` 无法带 Bearer） | `client.test.ts`：票据只进请求头（URL 与请求体不含令牌）；`protocol.test.ts`：信封四种失败分支；`sse` 用例：心跳、多行 data、CRLF、截断、多字节 |
| 2. 按 seq 去重、重连取快照、幂等提交 | `createSeqTracker` 丢弃重复/乱序；`streamRunEvents` 返回 `lastSeq` + `reason`，重连带 `afterSeq`；窗口过期改读快照；受理请求带 `Idempotency-Key` 并由服务端 `reused` 判定幂等 | `client.test.ts`：重复 seq 丢弃、`closed → afterSeq=4 → terminal` 重连链、窗口过期读快照且**不重新受理**；SDK 用例同上 |
| 3. 不复用 ADMIN Cookie 刷新逻辑 | 401 只调用宿主 `exchangeTicket()` **一次**并重试一次，再失败即抛错；模块内无 Cookie、无 storage、无隐式续期 | `client.test.ts`/SDK 用例：`exchangeTicket` 恰好调用一次；第二次 401 直接失败 |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-014 SSE 断线重连（seq 去重、恢复或读取快照、不重执行） | 通过（客户端侧） | `client.test.ts`：断线返回 `closed`+`lastSeq`，重连带 `afterSeq`；窗口过期读快照；去重丢弃重复/乱序 seq |
| AT-015 SSE 开流后错误（run.failed 终态、无假成功） | 通过（客户端侧） | 终态（`FAILED`/`CANCELLED`/`SUCCEEDED`）即停止订阅；HTTP/业务失败抛稳定错误（不返回空结果） |
| token 不在 URL/storage | 通过 | 用例断言 URL 与请求体不含令牌、只有 `Authorization` 头；模块不引用任何 storage API |
| 多字节分块与事件截断解析正确 | 通过 | `protocol.test.ts`：UTF-8 字符跨块、半帧缓冲后补齐、CRLF、多行 data、flush |
| 401 只触发一次换票 | 通过 | 两个客户端的 401 用例（`exchangeTicket` 调用次数断言） |

## 3. 关键约束落地

- **不新增依赖**：`@vben/ai-embed-sdk` 只依赖 `@vben/ai-contracts`（不依赖 Vue 组件包），
  因此**协议层**（信封/SSE/seq）放在 `ai-contracts` 供两个消费方共用，传输层各自实现；
  依赖图与锁文件未改动。C06 若让 SDK 依赖 chat-ui 客户端，可把传输层收敛为一处（已在 SDK 注释与本文件记录）。
- **不降级门禁**：未改任何门禁脚本、阈值或锁文件。
- **无凭据落盘**：不写 storage、不拼 URL、不打日志；SDK 不接触应用客户端凭据（换票由宿主负责）。
- **类型即契约**：SDK 的 `RunAccepted`/`RunSnapshot` 已从占位形状对齐平台契约（见"上游契约差异"）。
- **无 TODO/假数据/空实现**：所有分支都有实现与用例。

## 4. 实际执行的命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（前端命令在 `前端代码/basic-framework-admin`）。

| 命令 | 结果 |
|---|---|
| `pnpm exec vitest run --dom packages/ai-contracts/src/client packages/ai-chat-ui/src/client packages/ai-embed-sdk/src` | exit 0；`Tests 18 passed`（5 + 7 + 6） |
| `pnpm run check:type` | exit 0（37/37 任务） |
| `pnpm exec eslint <本卡新增/修改文件>` | exit 0 |
| `sh .harness/verify.sh contracts` | exit 0 |
| `sh .harness/verify.sh frontend` | exit 0（补棘轮登记后）：`check`（含 vue-tsc 37/37）+ `lint` + `test:coverage`（`Test Files 337 passed`，行覆盖 90.75% ≥ 阈值 81%）+ 生产构建 + `Production JavaScript: 228 files passed no-undef validation`；首次运行仅因 3 个新文件"尚未登记单文件覆盖率基线"失败（新增文件的预期状态） |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | exit 0：登记 3 个新文件基线（`protocol.ts`、`sse.ts`、`ai-chat-ui/src/client/index.ts`）；既有 `ai-embed-sdk/src/client.ts` 基线（100%）通过补测保持不降（新增 3 例覆盖错误分支与事件流头部）；`all 单文件基线通过` |

## 5. 新增/变化的对外契约

- **前端 API**：`createOpenApiClient`（chat-ui 共享客户端）与 `createAiChatClient`（宿主 SDK）的端点对齐
  开放 API 契约（`/app-api/ai/run/*`）；事件流返回 `{lastSeq, reason, snapshot?}`。
- **上游契约差异（需记录）**：SDK 原 `client.ts` 使用 `/app-api/ai/v1/runs*` 与字符串 `runId`（F04 期占位），
  与 O07 冻结的开放 API 契约不一致；本卡改为真实端点与数值编号 + `runKey`，属**修正占位**而非放宽契约。
- **字段/权限/迁移**：无。

## 6. 未验证项与已知边界

1. **真实浏览器复连验收未完成**：AT-014 的浏览器侧（真实 EventSource 断线、多标签、窄带）由 Q06/G5 承接；
   本卡的证据是**协议与客户端行为**的单元级证据（fetch/SSE 替身），不声称已通过浏览器验收。
2. **传输层两处实现**：chat-ui 客户端与 SDK 客户端各自持有 fetch 传输（协议解析共用）。这是依赖图的
   约束（SDK 不依赖 chat-ui）下的取舍；C06 建立 SDK↔chat-ui 依赖后可收敛。
3. **服务端真实推送模型**：当前订阅是"按 seq 重放 + 心跳注释"的有界实现（O05 记录），
   长时间连接会由服务端主动结束 → 客户端按 `closed` 重连，属预期行为。
4. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy DB 镜像不可达仍失败（见前序卡记录）。
