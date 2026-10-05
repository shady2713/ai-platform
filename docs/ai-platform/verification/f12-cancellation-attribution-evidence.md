# F12 出站取消与连接失败可区分 — 证据（2026-10-05）

本记录是 [F12 出站取消与连接失败可区分](../tasks/F12.md) 的验收证据。
依赖 [F09](f09-outbound-http-evidence.md)（受控出站边界）与 [F11](f11-outbound-streaming-evidence.md)（流式入口）。

模块：`后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-ai`

**本卡只改错误归因，不加能力。** F11 收尾实测发现：调用方主动取消一个出站请求时收到的异常是

```
CompletionException(ExternalHttpException: 出站请求失败)   Reason = CONNECT_FAILED
```

根因在 `GuardedExternalHttpClient.mapFailure`：`CancellationException` 不匹配任何既有分支，落到兜底，
被标成 `CONNECT_FAILED` + "出站请求失败"。**这不是"含糊"，是"错误"**——取消是调用方自己的决定，
与网络和上游无关，报成连接失败会把排查方向直接引到根本没问题的东西。`Reason` 当时只有 6 个取值，
没有地方安放这个区分，于是本卡补第 7 个。

## 1. 交付内容

| 交付物 | 位置 | 说明 |
|---|---|---|
| 词表新增取值 | `core/http/ExternalHttpException.java:26`（37→44 行） | `Reason.CANCELLED`——第 7 个稳定原因，**未改动**原有 6 个取值 |
| 失败映射新增分支 | `core/http/GuardedExternalHttpClient.java:169-186` | `mapFailure` 增加取消分支，位置在超时与连接判定**之前**；消息固定 `出站请求已被调用方取消` |
| 取消判定 | `core/http/GuardedExternalHttpClient.java:189-208` | `isCancellation`：**只剥纯包装**（`CompletionException`/`ExecutionException`），遇到其它类型即停；深度上限 `MAX_CAUSE_DEPTH = 8` |
| 两入口共用等待与归因 | `core/http/GuardedExternalHttpClient.java:96-104` | 新增 `await(CompletableFuture)`；`execute` 与 `openStream` 都只经它等待并归因（288→332 行） |
| 专项用例 | `core/http/OutboundCancellationAttributionTest`（新增，368 行 / 11 例） | 1 正向 + 7 反向 + 映射矩阵 + 消息卫生 + 两入口对齐 |
| 既有取消用例补注 | `core/http/GuardedExternalHttpClientTest.java:262-274`（545→547 行） | **仅加注释**指向 F12；断言一字未删（第 6 节） |
| 策略文档 | `docs/security/outbound-http-boundary.md` | 词表补 `CANCELLED` + 一条"取消必须与连接失败可区分" |
| F09 证据增补 | `docs/ai-platform/verification/f09-outbound-http-evidence.md` §6 | 记录词表从 6 值到 7 值的演进与未改动的既有归因 |

**未改动**：`AiHttpProperties`（取消不是配置项，**一个字节未改**）、`ExternalHttpClient`、
`ExternalHttpRequest`、`ExternalHttpResponse`、`BoundedExternalHttpStreamResponse`（F09/F11 冻结契约）、
契约台账 `version`（`data-lifecycle.json`=1、`data-permission-exemptions.json`=3）、`coverage-baseline.json`、
父 POM、依赖版本、前端、只读源框架 `E:\kuangjia\2026-main`。

## 2. 与卡片逐步实施的对应

1. **给 `Reason` 增加 `CANCELLED` 并映射 `CancellationException`**：完成。消息由"出站请求失败"
   改为"出站请求已被调用方取消"——说清是**已取消**而不是**失败**。旧文案保留，但**只**服务于
   真正的未知失败（兜底分支），不再覆盖取消。
2. **保持取消的传播语义**：完成，且**只改归因**。取消后调用方**仍然拿不到任何响应**，
   F11/M07 已钉住的性质与断言全部原样保留（第 6 节）。
3. **检查 F11 的流式入口是否走同一条映射**：发现**原本不是**，已一并对齐（第 5 节）。

## 3. 修复前后的对照（可证伪）

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 调用方取消 | `CONNECT_FAILED` + "出站请求失败" | **`CANCELLED`** + "出站请求已被调用方取消" |
| 真正连接失败 | `CONNECT_FAILED` + "无法连接出站目标" | **完全不变** |
| 真正超时 | `TIMEOUT` + "出站请求超时" | **完全不变** |
| 其它未知失败（兜底） | `CONNECT_FAILED` + "出站请求失败" | **完全不变** |

"无法连接出站目标"与"出站请求失败"两句文案同时存在这件事本身就是证据：修复前**真正的连接失败**
与**其它未知失败**挤在同一个 `CONNECT_FAILED` 上，取消混在里面，运维无从分辨该查网络还是查上游。

## 4. 正向证据：取消 → `CANCELLED`

用例：`callerCancellationIsAttributedToCancelledAndNotToConnectFailureOrTimeout`

端到端，不靠 mock：本地 `com.sun.net.httpserver.HttpServer` 提供 `/blocked` 端点，
**先用 latch 确认请求已抵达服务端**（否则 `cancel` 可能落在"future 已完成"的竞态上，
`cancel` 必然返回 false，与被测行为无关——这是 F11 第 7.1 节留下的教训），再 `future.cancel(true)`。
读超时给到 30s，确保测的是"取消"而不是"超时"。

观测结果：`join()` 抛 `CompletionException`，其 cause 为 `ExternalHttpException`，
`getReason() == CANCELLED`，`getMessage() == "出站请求已被调用方取消"`。

两条"看起来同样合理"的错误归因**分别断言**（只看上面一条不够——最可能的回归就是把取消塞进
这两个既有取值里的某一个）：

- `getReason()` **`isNotEqualTo(CONNECT_FAILED)`**
- `getReason()` **`isNotEqualTo(TIMEOUT)`**

同时断言取消后**不返回任何响应**：`future.isDone()` 为真，`join()` 抛异常，
且 `whenComplete` 记录到的响应对象为 `null`。

## 5. 反向证据（本卡价值所在）

"取消现在能识别了"证明不了任何事——只测正向的话，把 `CONNECT_FAILED` 顺手改掉这类回归会全部漏网。
以下用例逐条钉住**其它分支没有被带偏**。

| # | 用例 | 断言 | 结果 |
|---|---|---|---|
| 1 | `realConnectFailureIsStillAttributedToConnectFailure` | 真实连接被拒（`findClosedPort` 取一个无人监听的端口）⇒ `CONNECT_FAILED`；消息含"连接"、不含"取消" | 通过 |
| 2 | `realTimeoutIsStillAttributedToTimeout` | 真实慢响应（读超时 300ms）⇒ `TIMEOUT`；消息含"超时"、不含"取消" | 通过 |
| 3 | `aRealIoFailureIsNotMisreadAsCancellationJustBecauseItsChainMentionsOne` | `IOException("connection reset", cause = CancellationException)` **仍为 `CONNECT_FAILED`** | 通过 |
| 4 | `everyReasonValuePassesThroughUnchangedAndCancellationIsInTheWordList` | 遍历 `Reason.values()` **7/7** 逐个断言原样透传（`isSameAs`）；并断言词表含 `CANCELLED` | 通过 |
| 5 | `cancellationIsRecognisedThroughEveryCompletionWrapperShape` | 四种包装形态——裸 / `CompletionException` / `ExecutionException` / 双层 `CompletionException`——**均 `CANCELLED`** | 通过 |
| 6 | `timeoutConnectAndUnknownCausesKeepTheirExistingAttribution` | `HttpTimeoutException`→`TIMEOUT`；`ConnectException`、`UnknownHostException`、`IOException`、`IllegalStateException`→`CONNECT_FAILED`（**兜底未动**） | 通过 |
| 7 | `deeplyNestedWrappersTerminateWithAStableErrorInsteadOfLooping` | 20 层深层嵌套仍收敛为稳定 `ExternalHttpException`（解包有深度上限，不递归失控） | 通过 |
| 8 | `bothBlockingEntriesShareOneAwaitSoCancellationCannotStayIndivisible` | 同一 `await`：取消→`CANCELLED`、超时→`TIMEOUT`、正常→返回值（详见第 7 节） | 通过 |
| 9 | `streamingEntryKeepsItsOwnTimeoutAndConnectFailureAttribution` | 流式入口 `TIMEOUT`/`CONNECT_FAILED` 未变 | 通过 |

第 3 条是本卡特有的反向风险：`isCancellation` 若沿**真实 IO 失败的因果链**搜索取消，
就会把连接失败反向误判成取消——那是与本卡目标方向相反的错误。因此取消识别**只允许剥
`CompletionException`/`ExecutionException` 这类纯包装**，遇到其它类型立即返回 `false`。
深度上限常量沿用仓库既有约定（`basic-framework-common` 的 `SafeExceptionLogUtils.MAX_CAUSE_DEPTH = 8`）。

## 6. F11/M07 既有断言零删减

`GuardedExternalHttpClientTest.cancellationStopsPendingRequest`（F09 建立、F11 第 7.1 节收窄）
**断言一字未改**，仍断言 `isDone()` 与"取消后 join 必须失败、绝不能返回任何响应"
（`isInstanceOf(CompletionException.class)`）。本卡**只在该用例的注释里补了一句**指向
`OutboundCancellationAttributionTest`——因为 F11 已刻意把该用例收窄为只钉"拿不到响应"，
把稳定码的断言位置让给 F12。两处分工互补，不冲突。

`GuardedExternalHttpStreamTest` 16 例（含"超限有界终止"、五条通道治理反向）全部原样通过。

## 7. 流式与同步已对齐

检查结果是：**`openStream` 原先并不与 `execute` 走同一条映射**。

- 两者原先各自写了一份 `try { … .join() } catch (CompletionException)`，靠人工保持一致；
- 而 `join()` 在 future 被取消时抛的是**裸 `CancellationException`**（不包 `CompletionException`），
  两处 `catch` 都接不住它 ⇒ **原始 `CancellationException` 会直接漏给调用方**，
  即"同步可区分、流式仍不可区分"。

修复：抽出 `await(CompletableFuture)`（`GuardedExternalHttpClient.java:96`），`execute` 与 `openStream`
**都只经它等待并归因**。单点映射因此是**结构性保证**，而不是两处代码靠人工同步。
`bothBlockingEntriesShareOneAwaitSoCancellationCannotStayIndivisible` 直接覆盖 `await` 的三条路径
（取消 / 失败 / 正常），`streamingEntryKeepsItsOwnTimeoutAndConnectFailureAttribution` 钉住流式侧其余归因未变。

## 8. 消息卫生

用例：`cancellationMessageEchoesNeitherRequestBodyNorCredentials`

发一个带凭据与请求体的在途请求（`Authorization: Bearer sk-live-CREDENTIAL-MARKER`、
`X-Trace` 与 POST body 均为 `sk-live-BODY-MARKER-should-never-be-echoed`），取消后断言异常消息：

- 含"取消"（说清是已取消）
- **不含** body 标记、**不含**凭据、**不含** `sk-live`、**不含** `Bearer`
- **不含**目标 URL、**不含**上游 body（`late-response`）

消息是常量文案，不回带 `cause` 的消息；`cause` 本身按 F09 的既有做法保留在异常上供诊断
（`ExternalHttpException(reason, message, cause)`，与其余 5 个分支一致）。

## 9. 两处范围外改动（编译必需，主会话已裁决接受）

卡片 §3.1 要求给 `Reason` 加枚举常量，但**两个范围外的文件对 `Reason` 做了无 `default` 的
穷尽 `switch`**。Java 17 的箭头 `switch` 表达式必须穷尽，**加常量必然编译失败**——
不是风格问题，是构建直接断：

| 文件 | 所属卡的范围 | 改动 | 行为 |
|---|---|---|---|
| `provider/springai/GuardedExternalHttpTransport.java:93` | F11 范围 | `case CONNECT_FAILED, RESPONSE_TOO_LARGE` → `case CANCELLED, CONNECT_FAILED, RESPONSE_TOO_LARGE` | 仍 `UPSTREAM_FAILED` |
| `module-ai/…/webhook/AiWebhookDeliverySender.java:156` | X10 范围 | `case CONNECT_FAILED` → `case CANCELLED, CONNECT_FAILED` | 仍**可重试**的 `CONNECT_FAILED` |

两处都是把 `CANCELLED` **并入取消原本就到达的结论**：F12 之前取消被兜底成 `CONNECT_FAILED`，
所以这两个消费者今天看到的就是 `CONNECT_FAILED` / 可重试。**行为逐字节不变**，
只是让 `Reason` 词表在编译期穷尽，不存在语义变更，也不动任何既有分支的归因。

**主会话已裁决接受**（编译不过的替代方案严格更差）。补充说明：**没有**去改
`docs/ai-platform/tasks/index.json` 的 `allowedPaths` 给自己扩权——那是卡片定义，不该由执行者改。

## 10. 覆盖率陷阱：4 行不可达会击穿 100% 棘轮

这一节值得单独记，因为它是一个**真实踩中并修掉**的坑。

初版实现把 `catch (CancellationException)` **分别写在 `execute` 与 `openStream` 里**（第 7 节说的
"两处 catch 都接不住"，那就各接一处）。编译通过、正向用例也过，但覆盖率立刻掉到 **97%**：

```
GuardedExternalHttpClient.java   LINE 97.0% (131/135)   ← 4 行完全未覆盖
FULLY MISSED line 87: } catch (CancellationException exception) {
FULLY MISSED line 90: throw mapFailure(exception);
FULLY MISSED line 134: } catch (CancellationException exception) {
FULLY MISSED line 136: throw mapFailure(exception);
```

原因是结构性的：**没有任何测试能从外部取消 `execute`/`openStream`**——两者都不把可取消的句柄
交给调用方（`executeAsync` 的 future 由 `execute` 自己持有；`openStream` 是同步 API），
所以这 4 行**端到端不可达**。

为什么必须解决：`docs/contracts/coverage-baseline.json` 给
`GuardedExternalHttpClient.java` 登记的**基线是 100%**，而 `scripts/check-coverage-ratchet.mjs`
的 `baselineFailures` 明确**禁止下调基线**、只升不降。97% 会让 backend 门禁在覆盖率棘轮上
**直接失败**——而卡片本身并没有要求覆盖率下降。

修法不是加注释豁免，而是**让这行代码可测**：把两处合并为共用的 `await`，并让测试直接对它
传入一个已取消的 future（`bothBlockingEntriesShareOneAwaitSoCancellationCannotStayIndivisible`）。
结果回到 **100.00% (131/131)**，同时顺带解决了第 7 节"两处人工同步"的结构问题——
**覆盖率陷阱与结构缺陷指向同一个修法**。

## 11. 验证结果与覆盖率

```sh
cd 后端代码/basic-framework-boot
S=basic-framework-core/basic-framework-spring-boot-starter-ai
```

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -q -o -pl $S spotless:apply` | 0 | 每次改 Java 后执行；127 个文件 clean |
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 范围外那 1 个文件也格式化 |
| `./mvnw -o -pl $S clean test` | **0** | **331 例通过，失败 0，错误 0，跳过 0**（320 ⇒ **+11**） |
| `./mvnw -o -pl $S jacoco:report` | 0 | 生成模块报告 |
| `./mvnw -o -pl basic-framework-module-ai -am test -Dtest=AiWebhookDeliverySenderTest -Dsurefire.failIfNoSpecifiedTests=false` | **0** | `AiWebhookDeliverySenderTest` **8/8**；reactor BUILD SUCCESS（验证第 9 节的改动能编译且不回退） |

专项计数：`OutboundCancellationAttributionTest` **11 例**（新增）。
既有：`GuardedExternalHttpClientTest` **22 例**（F09 基线，未变）、`GuardedExternalHttpStreamTest` **16 例**（未变）。
另以 surefire 报告独立汇总复核：41 个测试类 / 331 例 / 失败 0 / 错误 0 / 跳过 0。

**覆盖率（sourcefile 口径，LINE）**

| sourcefile | 行覆盖 | 分支覆盖 | 棘轮基线 | 结论 |
|---|---|---|---|---|
| `core/http/GuardedExternalHttpClient.java` | **100.00%** (131/131) | 87.8% | 100 | 达标 |
| `core/http/ExternalHttpException.java` | **100.00%** (15/15) | 100% | 100 | 达标 |
| `provider/springai/GuardedExternalHttpTransport.java` | **100.00%** (29/29) | 90.5% | 100 | 达标 |
| `module-ai/…/AiWebhookDeliverySender.java` | 未单独取数（范围外，只加了 case 标签、无可执行行；该文件基线 100，8/8 用例通过） | — | 100 | 见第 12 节第 4 条 |

三个改动的 main sourcefile 均为 100% 行覆盖，**均不低于各自棘轮基线**；新增用例逐个覆盖
`Reason.values()` 的 7 个取值与 4 种包装形态（纯映射分支最易漏，故按取值逐个测）。

## 12. 未验证项

1. **流式入口的端到端取消无法构造**：`openStream` 是同步 API 且**不交出可取消的句柄**，
   调用方终止流的方式是 `close()`（F11 已钉住 `closingTheStreamLetsUpstreamObserveTheAbort`）。
   因此"流式端到端取消"没有 socket 级用例；`await` 的取消分支由直接单测覆盖（第 7 节）。
2. **`InterruptedException` 仍被报成 `CONNECT_FAILED`**：兜底分支会把线程中断（例如 `close()`
   期间的 `executor.shutdownNow()`）收敛为 `CONNECT_FAILED` + "出站请求失败"，与取消同类的
   错误归因。**按卡片铁律 4（不得为让取消可区分而改动其它分支的既有归因）本卡未动**，
   仅登记为相邻缺陷，是否单开一卡待定。
3. **`readChunk` 的上游中断归因正确，未动**：`BoundedExternalHttpStreamResponse` 把上游中断
   （`IOException`）映射为 `CONNECT_FAILED`——那是上游**真的断了**，与"调用方主动取消"性质不同，
   F11 已钉住该断言（`boundedReaderMapsReadFailureToStableReasonAndClosesOnce`）。
4. **覆盖率数字来自本模块自身的 `jacoco:report`，不是棘轮读的 integration 聚合报告**；
   **棘轮基线未更新**（本卡按铁律未跑任何门禁与 `check-*.mjs`）。棘轮只能在 integration 之后核验。
5. **`module-ai` 只跑了 `AiWebhookDeliverySenderTest` 一个类**（范围外改动的最小必要验证）；
   其余 `module-ai` 与 server 的测试、以及 `AiWebhookDeliveryIT` 未在本卡执行。
6. **真实出站调用仍未验证**：与 F09 的同一未验证项——平台尚无消费方，全部用例打在本机
   `HttpServer` 上，不访问外网。

## 13. 范围声明

- **允许范围内**：`core/http`（`ExternalHttpException`、`GuardedExternalHttpClient`）与其
  `src/test`（新增 `OutboundCancellationAttributionTest`、既有 `GuardedExternalHttpClientTest` 仅加注释）、
  模块外加 2 个文档（`docs/security/outbound-http-boundary.md`、F09 证据 §6）。共 7 个文件。
- **范围外但编译必需**：`provider/springai/GuardedExternalHttpTransport.java`、
  `module-ai/…/AiWebhookDeliverySender.java`，各 1 个 case 标签、**行为逐字节不变**，
  理由与主会话裁决见第 9 节。
- **未改动任何既有分支的归因**：`TIMEOUT`、`CONNECT_FAILED`（含 `ConnectException` /
  `UnknownHostException` / 兜底未知失败）、`TARGET_NOT_ALLOWED`、`PRIVATE_TARGET_DENIED`、
  `INVALID_REQUEST`、`RESPONSE_TOO_LARGE` 全部保持原判定，逐条反向断言见第 5 节。
- **未削弱 F09/F11 已验证的性质**：允许清单、私网判定、协议约束、请求头卫生、响应上限、
  不跟随重定向、TLS 默认校验、"取消后不返回任何响应"全部原样，断言零删减（第 6 节）。
- **未改动**：契约台账 `version`（`data-lifecycle.json`=1、`data-permission-exemptions.json`=3）、
  `coverage-baseline.json`、`AiHttpProperties`、父 POM、依赖版本、前端、只读源框架。
- **未执行**：门禁脚本（`sh .harness/verify.sh`）、`check-*.mjs` 任何子命令、任何 git 写操作
  （无 commit / push / clean / checkout / reset）。
- **只读脚本**：`check-coverage-ratchet.mjs` 与 `coverage-baseline.json` **只被读取**（未执行），
  用于确认棘轮量纲是 LINE 且基线只升不降——这直接决定了第 10 节的修法。

## 14. 后续

- **交付索引登记**：`docs/ai-platform/verification/delivery-index.md` 中 F12 的证据列仍为 `—`，
  待主会话登记为 `f12-cancellation-attribution-evidence.md`（本卡只写本文件，未改索引）。
- **F11 证据的归属**：卡片 §5 要求"更新 F09/F11 证据"，但派发 F12 时
  `f11-outbound-streaming-evidence.md` **尚不存在**。本卡**没有代写** F11 的验收记录——
  那是 F11 的交付物，代写会污染那张卡的证据链。F11 作者随后已自行补齐
  （`f11-outbound-streaming-evidence.md`，225 行），归属清晰，本卡不重复其内容。
- **相邻缺陷**：第 12 节第 2 条（`InterruptedException` 归因）建议单开一卡。
- **X10 决策项**：被取消的 Webhook 投递现在**仍是可重试**的（与 F12 之前完全一致）。
  "取消是否应当重投"属于 X10 的产品决策，本卡只做归因、不改可重试性，留给该卡判定。
