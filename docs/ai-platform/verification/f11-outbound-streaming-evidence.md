# F11 受控出站支持流式响应 — 证据（2026-10-05）

本记录是 [F11 受控出站支持流式响应](../tasks/F11.md) 的验收证据。
交付代码见提交 `07c5e43`；F12 卡（`fe8a5c7`）见本文第 9 节。

模块：`后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-ai`
本卡起点问题登记在 `m07-outbound-transport-governance-evidence.md` 的"缺陷 4（观察项，未改）"：
守卫是请求/响应边界，SSE 响应被**整个读完**才交给 Reactor，于是流式不再增量到达、长流还会被
`RESPONSE_TOO_LARGE` **整体拒绝**。本卡改的正是这个契约。

## 1. 交付内容

| 交付物 | 位置 | 说明 |
|---|---|---|
| 流式入口契约 | `core/http/ExternalHttpStreamSupport` | `openStream(request)`；**与 `ExternalHttpClient.execute` 并存，不替换** |
| 流式响应契约 | `core/http/ExternalHttpStreamResponse` | `status/headers/declaredLength/deliveredBytes/readChunk(byte[])/close()` |
| 有界流式读取器 | `core/http/BoundedExternalHttpStreamResponse` | 读一块判一块；`deliveredBytes()` 恒不超过 `max-response-bytes` |
| 守卫实现 | `core/http/GuardedExternalHttpClient`（251→288 行） | 抽出 `prepare`（两个入口**共用**全部请求期闸门）并实现 `openStream` |
| 响应式适配器重接线 | `provider/springai/GuardedExchangeFunction`（147→212 行） | 改走 `openStream`，逐块下发；取消/异常/正常结束三条路径都释放上游连接 |
| 守卫级用例 | `core/http/GuardedExternalHttpStreamTest`（新增 16 例） | 增量、超限、请求期拒绝、超时、取消、读取失败、边界精度 |
| 通道级用例 | `provider/springai/OutboundStreamingGovernanceTest`（新增 9 例） | 增量到达 + **整包对照**、治理反向、超限有界终止、取消、fail-closed |
| 夹具增强 | `provider/springai/FakeOpenAiGateway`（162→256 行） | 逐事件 flush + 事件间延时 + 已写字节计数 + 客户端断开观测 |
| 计数装饰器扩展 | `provider/springai/OutboundGovernanceChannelTest`（420→434 行） | `RecordingHttpClient` 追加 `openStream` 并计入 `attempts`（纯追加，零删减） |
| 策略文档 | 模块 README 新增"流式响应（与请求/响应入口并存）" | 两入口对照表 + 三条要点 |

**未改动**：`ExternalHttpClient`、`ExternalHttpRequest`、`ExternalHttpResponse`、
`ExternalHttpException`、`AiHttpProperties`（F09 冻结契约，**一个字节未改**）、契约台账 `version`、
父 POM、依赖版本、前端、只读源框架。

## 2. 与卡片逐步实施的对应

1. **在守卫上增加流式入口，与既有入口并存**：新增 `ExternalHttpStreamSupport`，`GuardedExternalHttpClient`
   同时实现两个接口。两个入口的全部请求期判定（关闭检查 → 请求头卫生 → 允许清单主机/端口 → 私网判定
   → 协议约束 → 超时与请求体）都收敛到同一个 `prepare(request)`。因此"流式仍受同一份治理"是**结构性保证**，
   不是两处代码靠人工同步维护。超限判定从"读完再判"改为"读一块判一块"，以 `RESPONSE_TOO_LARGE` **终止流**。
2. **重新接线 `GuardedExchangeFunction`**：由 `Mono.fromCallable(execute → 单个 DataBuffer)` 改为
   `openStream` + `Flux.create` 逐块下发。读取在 `Schedulers.boundedElastic()` 上进行（不占用事件循环），
   取消时停止读取并关闭上游连接，`doFinally` 兜底覆盖完成/异常/取消三条路径。
3. **长流超限用例**：见第 5 节。

## 3. 增量到达的对照数据（本卡最核心的证据）

**不靠"断言能流"，而是测首块到达时上游已经写了多少字节**，并配一条**只能反向失败**的对照用例。

夹具 `FakeOpenAiGateway` 在流式模式下逐事件 `flush()` 并在事件之间延时（开发期测量用 250ms 间隔，
最终提交为 600ms 以提高余量）。生产接线（`SpringAiModelClientFactory` → `GuardedExchangeFunction`）
在两条用例里完全相同，**唯一差别是守卫的响应体交付方式**。

| | 首块到达时上游已写 | 首块时刻 | 末块时刻 | 间隔 |
|---|---|---|---|---|
| **F11 流式入口** | **343 / 357 字节**（上游尚未写完） | 262ms | 514ms | **252ms** |
| 对照：守卫换成"整包才交付"（复刻 M07 算法） | **357 / 357 字节**（上游已全部写完） | 3699ms | 3703ms | **4ms** |

对照用例的守卫是测试内的 `BufferingGuard`：`openStream` 内部改调 `execute` 整包读完，
再把整包作为"一次 `readChunk`"交付——请求期治理仍由真实守卫决定，只有交付方式退化为 M07 的降级。
**它在同一条断言上给出相反结果（357/357、间隔≈0ms），证明增量断言不是恒真。**

提交版断言（`EVENT_DELAY_MILLIS=600`）：

- 流式：`writtenAtFirstDelta < totalStreamBytes()`（上游未写完）、`firstDeltaMillis ≥ 300`、
  `lastEventMillis - firstDeltaMillis ≥ 300`
- 对照：`writtenAtFirstDelta == totalStreamBytes()`（上游已写完）、`firstDeltaMillis ≥ 600`

守卫层另有字节级证据 `firstChunkArrivesBeforeTheStreamIsFinished`：首块交付时断言上游已写 < 总量，
且首块内容就是第一块的内容（不是等全部读完后才交付的整包）。

> 上表数字由开发期临时探针测得（探针已移除，仓库中无残留）；提交版用例的断言结构与上表同源。

**过程中确认的一个坑**：`javap` 实测 Spring AI 1.1.8 的 `chatCompletionStream` 是
`retrieve().bodyToFlux(String.class)` 后逐个 `ModelOptionsUtils.jsonToObject`，即**按 DataBuffer 逐个解析
JSON**——若块边界落在 SSE 事件中间就会解析失败。实测 `ServerSentEventHttpMessageReader.canRead` 对
`text/event-stream` 返回 true，且它自行按事件重新组帧并剥掉 `data:` 前缀，因此按网络块原样下发是安全的；
**前提是 `content-type` 必须原样透传**（适配器按 `Map<String,String>` 小写键原样 `headers::set`，
不是 `HttpHeaders`）。

## 4. 治理未被削弱（反向证据）

只跑正常路径等于没做本卡。反向用例分两层，都用**会被守卫拒绝的请求**构造。

| 用例 | 断言 |
|---|---|
| `streamingToNonAllowlistedTargetIsRefusedBeforeTheRequestLeavesTheProcess` | 夹具端点在允许清单内（构造期校验必然通过）但环回未显式批准 ⇒ 构造期放行、**请求期被拒**；平台侧稳定码 `TARGET_NOT_ALLOWED`；消息不含 `sk-guard-test` 与 `127.0.0.1`；`attempts==1`（不被重试放大）；**`gateway.requestCount()==0`（请求根本没离开进程）** |
| `streamingEntryRefusesHostPortSchemeAndCredentialHeader` | 四道闸门逐条断言：主机不在清单 `TARGET_NOT_ALLOWED`、端口不在清单 `TARGET_NOT_ALLOWED`、未批准内网时 http `TARGET_NOT_ALLOWED`、携带 `Cookie` 头 `INVALID_REQUEST`；全部在 DNS 解析与建连之前，**不产生任何外网流量** |
| `hostOutsideAllowListIsRefusedBeforeAnythingIsSent`（守卫级） | 同上，且服务端收到的请求数为 0 |
| `credentialHeaderIsRefusedByGuardRuleBeforeAnythingIsSent`（守卫级） | F09 请求卫生规则在流式入口同样生效 |
| `boundaryWithoutStreamingSupportFailsClosedInsteadOfFallingBackToBuffering` | 不支持流式的出站实现**显式失败**（`UPSTREAM_FAILED`／"受控出站边界不支持流式响应"），**不退回整包读取**——退回去等于在治理边界上开一个静默口子 |

## 5. 长流超限：有界终止 + 稳定错误码

`longStreamIsTerminatedWithStableCodeAndKeepsAlreadyDeliveredDeltas`：上限 1500 字节 vs 长流约 90 KiB
（600 个事件）。

- **稳定错误码**：平台侧 `UPSTREAM_FAILED`；守卫侧 `ExternalHttpException.Reason.RESPONSE_TOO_LARGE`
  （沿因果链断言）；消息不含凭据与目标地址。
- **已发增量不被回收为完整结果**：已交付增量非空，且事件类型中 `doesNotContain(COMPLETED)`
  ——超限后不会再出现"流正常结束"事件。
- **有界终止而不是"读完再判"**：夹具观测到连接被释放（后续写入失败），且**上游没把整个长流写完**
  （`streamBytesWritten < totalStreamBytes`）。
- **边界精度**（守卫级）：`deliveredBytes() ≤ 上限`；越界的那一块不计入交付
  （`boundedReaderDeliversUpToTheLimitAndThenReportsStableReason`）；流恰好等于上限时**不误判**为超限
  （`boundedReaderEndsCleanlyWhenTheStreamStopsExactlyAtTheLimit`）。

> 刻意的一点：流式入口**不**按声明长度提前拒绝（否则长流退化成 M07 的"整体拒绝"，与本卡目标相反）。
> 声明长度只作诊断信息，已写入 `BoundedExternalHttpStreamResponse` 的 javadoc。

## 6. M07 既有断言零删减

| 测试类 | M07 基线 | F11 后 | 处置 |
|---|---|---|---|
| `OutboundGovernanceChannelTest` | 13 | **13** | 仅给 `RecordingHttpClient` 追加流式能力并把 `openStream` 计入 `attempts`；**`@Test` 零删除、零放宽** |
| `GuardedExternalHttpClientTest`（F09 请求/响应入口） | 22 | **22** | 原样通过 |
| `GuardedExternalHttpTransportTest` | 18 | **18** | 原样通过 |
| `SpringAiEndpointIsolationTest` | 2 | **2** | 原样通过 |
| 模块总计 | 295 | **320** | +25（守卫级 16 + 通道级 9） |

M07 §5.1 的**五条通道反向断言逐条在位**（聊天同步、嵌入、转写、语音合成、聊天流式），
每条仍同时断言稳定错误码、`attempts==1` 与 `gateway.requestCount()==0`。
F09 原有请求/响应入口行为不变（22 例原样通过）。

`m02-model-client-factory-evidence.md` 未验证项第 1 条「请求级传输治理」保持**已验证（M07）**不变——
本卡只解决流式降级，不改变该结论；本卡未修改该文件。

## 7. 顺带修复的两处既有测试缺陷

以下两处是**既有测试**的缺陷（由主管复核发现，非本卡新增功能），修复理由与经过如实记录：

### 7.1 `GuardedExternalHttpClientTest.cancellationStopsPendingRequest`：断言对象错了

- **原症状**：断言 `future.isCancelled()`，负载高时随机变红。
- **根因**：用例拿到的 future 是 `.exceptionally(...)` 的**派生阶段**，而 `exceptionally` 会把上游 future 的
  取消映射成 `ExternalHttpException`。所以它既不是 `isCancelled()==true`（那是**上游 future** 的语义），
  也不是 `CancellationException`——断言这两者等于断言实现细节，**结果由时序决定**。
- **修法**：断言真正要保证的性质：**取消之后调用方拿不到任何响应**——
  `future.isDone()` 为真 + `future::join` 必然抛 `CompletionException`；并新增 `/slow-blocked` 端点，
  用闩锁（`slowRequestArrived` / `releaseSlowHandler`）先确认请求已抵达服务端、处理线程正阻塞，
  确保 `cancel` 打在**确定在途**的请求上（`finally` 放行线程，避免拖慢整类测试）。
- **刻意收窄**：本用例只钉"拿不到响应"这条性质；取消的**具体稳定错误码**由 F12 的
  `OutboundCancellationAttributionTest` 负责（见第 9 节）。

### 7.2 `AiWebhookDeliveryIT.cleanupBusinessChain`：清理不幂等

- **原症状**：`applicationId==null` 分支只删 7 张表里的 1 张，留下 6 张孤儿行，使该类**在复用库上不幂等**
  （第二轮 `enqueued=0`）。
- **修法**：改为按稳定键（`app_code` / `service_code` / `target_code`）**无条件清全链**——
  `ai_webhook_delivery_attempt` → `ai_webhook_delivery` → `ai_webhook_target` → `ai_run` →
  `ai_service_release` → `ai_service` → `ai_application`，不再依赖先前捕获的 `applicationId`；
  并对 `application_id` 指向不存在应用的**孤儿行**按 code 兜底清理一次。

## 8. 验证结果与覆盖率

```sh
cd 后端代码/basic-framework-boot
S=basic-framework-core/basic-framework-spring-boot-starter-ai
export PATH=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH
```

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -q -o -pl $S spotless:apply` | 0 | 每次改 Java 后执行 |
| `./mvnw -q -o -pl $S spotless:check` | 0 | 格式检查通过 |
| `./mvnw -o -pl $S compile` | 0 | 编译通过 |
| `./mvnw -o -pl $S test` | 0 | **320 例通过，失败 0，错误 0，跳过 0**（295 ⇒ +25） |
| `./mvnw -o -pl $S jacoco:report` | 0 | 生成模块报告 |

专项计数：`GuardedExternalHttpStreamTest` 16 例、`OutboundStreamingGovernanceTest` 9 例。
另以 surefire 报告独立汇总复核：40 个测试类 / 320 例 / 失败 0 / 错误 0 / 跳过 0。

**覆盖率（sourcefile 口径，脚本同卡片）**

| sourcefile | 行覆盖 | 未覆盖行 |
|---|---|---|
| `BoundedExternalHttpStreamResponse.java` | **100.00%** (38/38) | — |
| `GuardedExchangeFunction.java` | **100.00%** (54/54) | — |
| `GuardedExternalHttpClient.java` | **100.00%** (121/121) | — |
| `GuardedExternalHttpTransport.java` | 100.00% (29/29) | — |
| `SpringAiModelClientFactory.java` | 100.00% (108/108) | — |
| `SpringAiModelClient.java` | 98.94% (279/282) | 283、284、585（**M02 既有代码，本卡未改**） |

全模块 **68 个 sourcefile，低于 80% 的数量：0**。

补测的易漏分支：取消后再到达的**在途块被丢弃**（取消与在途数据的竞态）、取消释放上游连接、
`close()` 幂等、`close()` 失败不掩盖终止语义、读取 `IOException → CONNECT_FAILED`、
流恰好等于上限不误判、**上游半截响应**、空读（`read==0`）、不可用缓冲（空/`null`）。

## 9. 后续

- **`07c5e43` 之后 `fe8a5c7` 建了 F12（出站取消与连接失败可区分）**，依赖 F09 与 F11。
  它修掉的是一处**可证伪的错误归因**：此前"取消"会被兜底成 `Reason.CONNECT_FAILED`，
  即把"调用方主动取消"误报为"连接失败"。F12 的验收结论由 F12 作者写入 F12 证据，本文不代写。
  本卡第 7.1 节已把取消用例收窄为"拿不到任何响应"，为 F12 留出稳定码的断言位置。
- `m07-outbound-transport-governance-evidence.md` 的"缺陷 4（观察项，未改）"应改为已解决并指向本记录
  与 `07c5e43`（由主会话在合入后统一登记）。

## 10. 未验证项

1. **真实厂商端点未验证**：全部用例打在本机假 OpenAI 兼容端点上（AT-001/AT-004 同样如此）。
2. **`OpenAiAudioApi.stream`（音频流式）无运行期入口**（Q07 已确认平台当前不调用它），故无专项用例；
   它与聊天流式共用同一受控 `WebClient` / 同一 `openStream` 接线，能力已生效但未逐条断言。
3. **跨厂商互操作需真实第三方 MCP 服务器**：流式响应按 `content-type` 决定解码器，跨厂商的
   `text/event-stream` 变体（不同的分帧/心跳）未验证。
4. **`read-timeout` 只约束响应头阶段（实测，非回归）**：实测 `readTimeout=500ms` + 上游中途停顿 2s ⇒
   第二块**阻塞 1926ms 后正常返回，无 `TIMEOUT`**。两个入口用同一个 `HttpRequest.timeout()`
   与同一个 JDK 响应流，因此与 F11 之前（`execute` 路径）的语义**一致，不是本卡引入的回归**；
   流中途停顿由 M03 的 `stream-idle-timeout` 兜底（既有机制未改）。若产品要求单块读取也受
   `read-timeout` 约束，需另开卡（涉及超时语义变更，超出本卡范围）。
5. **上游 TCP 层硬断未验证**：`com.sun.net.httpserver` 的 `close()` 会补 chunked 终止块，造不出
   "半截响应"，故该路径改用编排流做**确定性**覆盖（`boundedReaderMapsReadFailureToStableReasonAndClosesOnce`），
   未在 socket 层验证。
6. **覆盖率数字来自本模块自身 `jacoco:report`**，不是棘轮读的 integration 聚合报告；
   **棘轮基线未更新**（本卡未跑任何门禁与 `check-*.mjs`，按卡片铁律由主会话三段式执行）。
7. **跨模块影响未在本卡执行**：`module-ai` 与 server 的测试未运行。可论证安全：F09 冻结契约
   `ExternalHttpClient` 接口**零改动**，范围外的实现类（连接器、Webhook 与各测试夹具）不受影响。

## 11. 范围声明

- **允许范围内**：只改了 `core/http`、`provider/springai`、这两个包对应的 `src/test` 与模块 README，
  共 10 个文件（已按修改时间核过）。新增文件全部落在 `core/http` 及其测试目录内。**未越界。**
- **未改动 F09 冻结契约**：`ExternalHttpClient`、`ExternalHttpRequest`、`ExternalHttpResponse`、
  `ExternalHttpException`、`AiHttpProperties` 一个字节都没改；流式能力用独立的能力接口表达，
  以免给 `ExternalHttpClient` 加抽象方法而强制改造范围外的实现（`AiBusinessWriteSimulator`
  与两处匿名实现）。
- **未削弱 M07 已验证的拒绝语义**：五条通道逐条断言保留；`execute` 入口语义完全未变。
- **未改动**：契约台账 `version`（`data-lifecycle.json`=1、`data-permission-exemptions.json`=3）、
  父 POM、依赖版本、前端、只读源框架 `E:\kuangjia\2026-main`。
- **未执行**：门禁脚本、`check-*.mjs` 任何子命令、任何 git 写操作。
