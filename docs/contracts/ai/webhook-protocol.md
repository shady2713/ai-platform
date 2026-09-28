# 受控异步结果 Webhook 协议 v1（X10）

把**运行终态结果**（`ai_run` 的 `SUCCEEDED`/`FAILED`/`CANCELLED`）投递给对接方登记的目标。
本文是平台与接收端的**同一份契约**：平台按此发送（`AiWebhookDeliverySender`），
接收端按此校验（可执行样例：`basic-framework-server` 测试夹具 `AiWebhookReceiverSample`，
在 `AiWebhookDeliveryIT` 里以真实 HTTP 接收端参与验收）。

## 1. 事件与目标

| 事件类型 | 触发事实 | 正文 `status` |
|---|---|---|
| `RUN.SUCCEEDED` | 运行进入成功终态 | `SUCCEEDED` |
| `RUN.FAILED` | 运行进入失败终态 | `FAILED` |
| `RUN.CANCELLED` | 运行被取消 | `CANCELLED` |

- 目标登记在 `ai_webhook_target`：应用 + 标识（应用内唯一，创建后不可修改）+ 投递地址 + 事件白名单 + 签名密钥。
- **只投终态**：中间态（`ACCEPTED`/`RUNNING`）不投递；需要中间进度请用运行事件流/进度查询。
- **只投本应用**：入队按"运行所属应用 = 目标所属应用"匹配；跨应用不投递。
- 事件白名单是**子集**：未订阅的事件不产生投递行（不会"默认订阅一切"）。
- **停用即停发**：目标停用后不再入队、发送前复检拒绝、人工重投被拒；已在途的投递按事实收尾。

## 2. 请求

```
POST <登记的目标地址>            # 地址由对接方提供；实际可否出站由受控出站边界决定
Content-Type: application/json; charset=utf-8
User-Agent: basic-framework-ai-webhook/1.0
X-AI-Webhook-Id: whd_<32 位十六进制>     # 投递编号：同一次投递的重试保持不变，接收端据此去重
X-AI-Webhook-Timestamp: <epoch 秒>       # 本次尝试的发送时间（每次尝试都重新取值）
X-AI-Webhook-Event: RUN.SUCCEEDED|RUN.FAILED|RUN.CANCELLED
X-AI-Webhook-Attempt: <1..n>             # 第几次尝试；仅用于观测，不参与签名
X-AI-Webhook-Resource: RUN:<runKey>      # 资源引用，便于接收端先路由再解析正文
X-AI-Webhook-Signature: v1=<hex(HMAC-SHA256)>
```

正文（`body`）是**入队时冻结**的规范化 JSON，重试复用同一份字节：

```json
{"schemaVersion":"1.0","eventType":"RUN.SUCCEEDED","resourceType":"RUN",
 "resourceKey":"run_7f3c91","status":"SUCCEEDED","occurredAt":"2026-09-27T10:30:05"}
```

正文**只有**状态与资源引用：不含提示词、模型响应正文、会话内容、上游地址、凭据与令牌。
`occurredAt` 是运行终态写入时间（本地时间，秒精度）；未知时不写该字段（不伪造成当前时间）。
字段顺序固定，`payloadDigest`（投递行 `ai_webhook_delivery.payload_digest`）= `sha256hex(body)`。

## 3. 签名与校验（接收端必须按顺序执行）

```
canonical  = timestamp + "." + deliveryNo + "." + sha256hex(body)
signature  = "v1=" + hex(hmacSha256(secret, canonical))
```

1. **必需请求头**：`X-AI-Webhook-Id` / `X-AI-Webhook-Timestamp` / `X-AI-Webhook-Signature` 缺失 → `400`。
2. **时间戳窗口**：`|now - timestamp| > 300s` → `401`（**过期时间戳**与未来时间戳都拒绝）。
3. **验签**：用共享密钥重算 `signature` 并**常量时间比较**（`MessageDigest.isEqual`）→ 不符 `401`（**伪造签名**）。
   注意：必须使用**收到的原始请求体字节**验签，不得重新序列化。
4. **去重**：投递编号已处理过 → `409` 并且**不做二次业务处理**（**重复投递**）。
   去重表按投递编号保留至少一个重试窗口（建议 ≥ 24 小时）。
5. 通过 → `200`（本平台把 2xx 记为已送达）。

拒绝响应的语义码（样例接收端在响应体里回显，便于平台侧排查）：
`missing-header` / `stale-timestamp` / `forged-signature` / `replayed-delivery`。

**密钥**：目标创建时由对接方提交（16–128 位），平台只保存 `CredentialCipher` 密文
（AAD 绑定目标编号，密文不可挪到别的目标行），轮换走独立接口并递增 `secret_revision`；
密钥不进日志、不进任何响应（管理端只返回 `secretConfigured` 与 `secretRevision`）。

## 4. 投递语义（至少一次 + 接收端去重）

| 平台侧行为 | 事实落点 |
|---|---|
| 同一「目标 × 事件 × 资源」最多一条投递行（唯一键兜底） | `ai_webhook_delivery`（`delivery_no` 唯一） |
| 入队即冻结正文与投递编号；重试复用同一份字节与编号 | `payload_json` / `payload_digest` / `delivery_no` |
| 领取是 CAS + 租约（owner + claimed_epoch），落结论带栅栏 | `status` / `lease_*` / `claimed_epoch` |
| 每次完成的尝试留痕（结论、HTTP 状态、原因码、签名时间戳、耗时） | `ai_webhook_delivery_attempt` |
| **投递失败绝不回写运行状态、绝不重跑运行** | 投递链路只读 `ai_run`，不写运行/任务表 |

**有界重试**：只有可重试结果重试，退避 `retry-base-seconds × 2^(attempt-1)`，上限 `retry-max-seconds`
（默认 30s 起、600s 封顶），次数上限取目标登记的 `max_attempts`（1–10，入队时快照）。
超限置 `FAILED` 并记 `failure_code = 1_003_011_006`（重试预算耗尽），最后一次的真实原因保留在
`last_error_code`。失败（死信）可由管理员人工重投：**追加一份与目标配置一致的重试预算**并立即入队（尝试计数与尝试序号继续单调递增，投递编号与正文不变，因此接收端去重语义不受影响）；目标已停用或删除时拒绝。

**结果分类**：2xx 送达；3xx **不跟随重定向**（确定失败，改址需人工重新登记目标）；
4xx（含接收端按编号去重返回的 409）确定失败；429 与 5xx 可重试；超时与连接失败可重试。

## 5. 出站边界

投递只经 F09 受控出站边界（`GuardedExternalHttpClient`）：

- 目标主机/端口必须命中 `basic-framework.ai.http.allowed-hosts` / `allowed-ports`，
  清单为空 = **拒绝一切目标**（零请求，确定失败）；
- 解析到环回/私网/链路本地等地址时必须有 `allow-private-targets=true` 的显式批准；
- 不跟随重定向；TLS 用 JVM 默认校验；响应体有大小上限；
- 请求头只由服务端构造，不转发任何入站头、Cookie 与宿主凭据。

登记目标时平台还会拒绝形状不合规的地址（非 http/https、缺主机、**URL 里带 `user:pass@` 凭据信息**、超长），
并把 `https` 以外的形态留给受控边界在发送前判定——登记期不复制允许清单，避免两份真值漂移。

## 6. 接收端样例（可执行）

`basic-framework-server/src/test/java/com/basicframework/server/integration/AiWebhookReceiverSample.java`
是一个**真实的本机 HTTP 接收端**（`com.sun.net.httpserver`），按 §3 的顺序实现校验与去重，
并支持故障注入（5xx / 4xx / 3xx / 超时且未生效 / 已生效但响应丢失）。
`AiWebhookDeliveryIT` 用它覆盖：正常送达、伪造签名、过期时间戳、重复投递、重定向不跟随、
未授权目标与私网目标零请求、停用即停发、有界重试与死信重投、响应丢失后副作用只发生一次。

对接方落地时按 §3 的五个步骤实现即可（任何语言）：**先验时间戳**、**再验签名**、
**再用投递编号去重**、**最后处理业务**，并对重复投递返回 `409`（或按你们的口径返回 2xx 幂等成功，
两种都能与本平台的投递语义共存：前者在平台侧记为确定失败，后者记为已送达）。
