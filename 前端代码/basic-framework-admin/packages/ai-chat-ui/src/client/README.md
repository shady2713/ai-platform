# 共享开放客户端（C01）

开放 API（`/app-api/ai/run/*`）的**唯一**前端客户端：Bearer 内存票据 + CommonResult/真实 HTTP 错误 + fetch SSE 解析 + seq 去重与重连。Chat UI（C02）与宿主 SDK（C06）共用同一份协议口径。

## 模块

| 文件 | 职责 |
| --- | --- |
| `packages/ai-contracts/src/client/protocol.ts` | CommonResult 信封解析、稳定错误 `AiOpenApiError`、seq 去重器（共享给 SDK） |
| `packages/ai-contracts/src/client/sse.ts` | SSE 帧解析（多字节安全、跨块截断安全、注释心跳、多行 data） |
| `packages/ai-chat-ui/src/client/index.ts` | `createOpenApiClient`：受理/查询/取消/分页/事件流；票据只进请求头；401 只换票一次 |

## 用法

```ts
import { createOpenApiClient } from '@vben/ai-chat-ui';

const client = createOpenApiClient({
  baseUrl: 'https://host/app-api',
  accessToken: () => hostTicket, // 宿主持有内存票据；本模块不持久化
  exchangeTicket: () => hostExchange(), // 401 时调用一次（不复用管理端 Cookie 刷新）
});

const accepted = await client.acceptRun({
  dataLevel: 'L2_INTERNAL',
  idempotencyKey: crypto.randomUUID(),
  message: '帮我看下 8 月销售',
  serviceId: 9,
});

await client.streamRunEvents(accepted.runId, {
  onEvent: (event) => render(event),
  onHeartbeat: () => markAlive(),
});
```

## 行为约定（与验收对应）

- **AT-014 断线重连**：`streamRunEvents` 返回 `{ lastSeq, reason }`；`reason=closed` 时用 `afterSeq: lastSeq` 重连（服务端只补发更大的 seq，客户端再按 seq 去重）；重放窗口过期（`1003004009`）时改为读取快照， **不重新发起运行**。
- **AT-015 开流后错误**：`run.failed` 是终态事件（`reason=terminal` 后停止订阅）；HTTP/业务失败抛稳定错误（`status` + `code`），不返回"看起来成功"的空结果。
- **票据不在 URL/storage**：令牌只通过 `Authorization` 头传递；本模块不写任何存储、不拼接 URL 参数。
- **401 只换票一次**：由宿主 `exchangeTicket()` 提供新票据并重试一次；再次 401 直接失败。
- **幂等提交**：受理请求由调用方生成幂等键（16..128），重试用同一键；`reused=true` 表示命中幂等。
