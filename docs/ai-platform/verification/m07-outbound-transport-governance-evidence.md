# M07 请求级出站传输治理 — 证据

日期：2026-10-01
仓库：`/home/ctyun/桌面/zhongtai/ai-platform`
模块：`后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-ai`

---

## 1. 缺口与本卡动作

M02 交付的 `SpringAiModelClientFactory` 只在**创建期**治理出站：端点快照校验允许清单
（主机 + 端口）、解析层拒绝停用端点。客户端构造用的是 Spring AI 默认传输：

```java
OpenAiApi openAiApi = OpenAiApi.builder()
        .baseUrl(snapshot.baseUrl())
        .apiKey(snapshot.apiKey())
        .build();
```

因此**每一个实际发出的请求都不再经过 F09 的 `GuardedExternalHttpClient`**：
允许清单、地址解析私网校验、协议（https）约束、不跟随重定向、响应大小上限、
请求超时这六项治理只对"创建客户端"生效，对"发请求"不生效。

本卡把两种传输都换成受控边界适配器，使每次出站请求重新过守卫。

## 2. 接线点：javap 实测，不凭记忆

Spring AI 1.1.8 / spring-web 6.2.19 / spring-webflux 6.2.19。

```sh
export PATH=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH
CP=$(find ~/.m2/repository/org/springframework/ai -name "spring-ai-openai-1.1.8.jar" | head -1)
javap -cp "$CP" 'org.springframework.ai.openai.api.OpenAiApi$Builder'
javap -cp "$CP" 'org.springframework.ai.openai.api.OpenAiAudioApi$Builder'
javap -p -c -cp "$CP" 'org.springframework.ai.openai.api.OpenAiApi'      # 逐方法看访问的是 restClient 还是 webClient
javap -p -c -cp "$CP" 'org.springframework.ai.openai.api.OpenAiAudioApi'
```

两个厂商 Builder 的客户端定制方法（退出码 0）：

| Builder | 可用定制点 |
|---|---|
| `OpenAiApi$Builder` | `baseUrl` / `apiKey` / `headers` / `completionsPath` / `embeddingsPath` / **`restClientBuilder`** / **`webClientBuilder`** / `responseErrorHandler` |
| `OpenAiAudioApi$Builder` | `baseUrl` / `apiKey` / `headers` / **`restClientBuilder`** / **`webClientBuilder`** / `responseErrorHandler` |

**没有** `clientBuilder`，也没有别的 raw HTTP 钩子。因此适配器只能落在两种 Spring 传输上：
`RestClient.Builder.requestFactory(ClientHttpRequestFactory)` 与
`WebClient.Builder.exchangeFunction(ExchangeFunction)`（6.2 的类型名是 `ExchangeFunction`，
7.0 才改名为 `ClientExchangeFunction`——按 javap 结果选型，不按记忆）。

`javap -p -c` 逐方法实测"哪个通道用哪个客户端"（字段引用即证据）：

```
OpenAiApi$Builder.restClientBuilder / webClientBuilder  -> 均存在
OpenAiApi.<init>                                      -> ['REST', 'WEB']
OpenAiApi.chatCompletionEntity                        -> ['REST']      ← 阻塞
OpenAiApi.chatCompletionStream                        -> ['WEB']       ← 响应式
OpenAiApi.embeddings                                  -> ['REST']      ← 阻塞
OpenAiAudioApi.createSpeech                           -> ['REST']      ← 阻塞
OpenAiAudioApi.stream                                 -> ['WEB']       ← 响应式
OpenAiAudioApi.createTranscription                    -> ['REST']      ← 阻塞
OpenAiAudioApi.createTranslation                      -> ['REST']      ← 阻塞（本平台未用）
```

四条通道**并不共用同一个客户端**：聊天同步/嵌入/转写/语音合成走 `RestClient`，
聊天流式与音频流式走 `WebClient`。只接 `RestClient` 会漏掉流式聊天这条真实路径
（`SpringAiModelClient.stream` → `OpenAiChatModel.stream` → `chatCompletionStream`），
因此两种传输都接线。

## 3. 变更文件清单

| 文件 | 类别 | 说明 |
|---|---|---|
| `provider/springai/GuardedExternalHttpTransport.java` | 新增（106 行） | 桥接器：产出两个 Spring 客户端构造器、请求头映射、拒绝原因→稳定原因的映射 |
| `provider/springai/GuardedClientHttpRequestFactory.java` | 新增（140 行） | 阻塞传输适配器（`ClientHttpRequestFactory` + 请求/响应视图） |
| `provider/springai/GuardedExchangeFunction.java` | 新增（147 行） | 响应式传输适配器（`ExchangeFunction` + 请求体缓冲目标） |
| `provider/springai/SpringAiModelClientFactory.java` | 改（233→284 行） | 构造器注入 `ExternalHttpClient`；四个通道全部接线；厂商重试模板统一为单次尝试；转写选项补 `responseFormat` |
| `provider/springai/SpringAiModelClient.java` | 改（703→723 行） | `mapFailure` 先沿因果链识别守卫拒绝，给稳定原因与稳定文案 |
| `config/BasicFrameworkAiAutoConfiguration.java` | 改 | 自动装配改为把 `ExternalHttpClient` Bean 传给工厂（构造器注入） |
| `test/.../FakeOpenAiGateway.java` | 新增（162 行） | 假 OpenAI 兼容端点：一台夹具同时提供四条通道的协议端点，可制造 5xx 与延迟 |
| `test/.../OutboundGovernanceChannelTest.java` | 新增（420 行） | 四通道逐条正向 + 反向 + 非回退用例 |
| `test/.../GuardedExternalHttpTransportTest.java` | 新增（368 行） | 适配器契约：请求头、请求级允许清单、原因映射、响应视图、响应式缓冲 |
| `test/.../SpringAiEndpointIsolationTest.java` | 改（+2 行） | 环回夹具端点显式 `setAllowPrivateTargets(true)`（见 §7 缺陷 3） |

未新增/未改动：`core/http/`（F09 守卫）、starter-ai 其它子包、父 POM、依赖版本、
契约台账（`data-lifecycle.json` / `data-permission-exemptions.json` 的 `version` 字段未动）、前端。

## 4. 四条通道逐条结果

正向用例的"经守卫"证据不是配置而是**计数**：测试里用一个只记录不改写的装饰器
`RecordingHttpClient` 包住**真实的** `GuardedExternalHttpClient`，
`attempts` 每次 `execute` 递增即证明该通道的请求走过守卫，放行与拒绝的判定都出自守卫。
同时断言夹具端点收到的路径与 `Authorization: Bearer sk-guard-test`。

| # | 通道 | 入口 | 厂商 API | 实际客户端 | 是否经守卫 | 断言内容 |
|---|---|---|---|---|---|---|
| 1 | 聊天（同步） | `ModelPort.generate` | `OpenAiApi` | `restClient` | ✅ `attempts==1` | 回复文本 `"同步回复"`；URL 以 `/v1/chat/completions` 开头；夹具收到该路径；凭据为 `Bearer sk-guard-test` |
| 1b | 聊天（流式） | `ModelPort.stream` | `OpenAiApi` | `webClient` | ✅ `attempts==1` | 增量文本拼出 `"流式"`；URL 结尾 `/v1/chat/completions`；请求体含 `"stream":true` |
| 2 | 嵌入 | `ModelPort.embed` | `OpenAiApi` | `restClient` | ✅ `attempts==1` | `dimensions()==2`；首条向量 `[0.5, 0.25]`；URL 结尾 `/v1/embeddings`；夹具收到该路径 |
| 3 | 转写 | `ModelPort.probe(SPEECH_TO_TEXT)` | `OpenAiAudioApi` | `restClient` | ✅ `attempts==1` | 探测 `SUPPORTED`；URL 结尾 `/v1/audio/transcriptions`；夹具收到该路径；凭据正确 |
| 4 | 语音合成 | `ModelPort.synthesizeSpeech` | `OpenAiAudioApi` | `restClient` | ✅ `attempts==1` | 音频字节等于夹具字节；URL 结尾 `/v1/audio/speech`；夹具收到该路径 |
| — | 音频流式 | 平台未使用 | `OpenAiAudioApi` | `webClient` | ✅（同一受控 `WebClient` 实例） | 无专项用例，见 §8 未验证项 1 |

通道 3 说明：平台侧 `ModelPort.transcribeSpeech` 未被 `SpringAiModelClient` 覆写
（`ModelPort` 默认实现按能力缺失拒绝），转写通道在运行期的唯一入口是
`probe(ModelProbeKind.SPEECH_TO_TEXT)`，因此用例走探测路径断言 `Status.SUPPORTED`。
这不是本卡的取舍，是对现状的如实记录。

## 5. 反向用例：出站到非允许清单地址在请求期被拒

只跑正常路径等于没做本卡。反向用例分两层，都用**会被守卫拒绝的请求**构造。

### 5.1 全通道反向（构造期放行、请求期拒绝）

夹具端点 `http://127.0.0.1:<port>`，策略 `allowedHosts=[127.0.0.1]`、`allowedPorts=[<port>]`、
**`allowPrivateTargets=false`**。主机在清单内、端口在清单内 ⇒ **创建期校验必然通过**
（`SpringAiModelClientFactory.validateSnapshot` 只查这两项）；守卫额外判定该地址是环回
且未显式批准 ⇒ **请求期拒绝**。

| 用例 | 通道 | 真实表现 | 稳定错误码 | 守卫调用 | 夹具收到请求 |
|---|---|---|---|---|---|
| `chatChannelIsRefusedAtRequestTimeWhenTargetNotAllowed` | 聊天同步 | 抛 `ModelException` | `TARGET_NOT_ALLOWED` | 1 | **0** |
| `embeddingChannelIsRefusedAtRequestTimeWhenTargetNotAllowed` | 嵌入 | 抛 `ModelException` | `TARGET_NOT_ALLOWED` | 1 | **0** |
| `transcriptionChannelIsRefusedAtRequestTimeWhenTargetNotAllowed` | 转写 | 探测 `FAILED` | `detailCode=TARGET_NOT_ALLOWED` | 1 | **0** |
| `speechChannelIsRefusedAtRequestTimeWhenTargetNotAllowed` | 语音合成 | 抛 `ModelException` | `TARGET_NOT_ALLOWED` | 1 | **0** |
| `streamingChatChannelIsRefusedAtRequestTimeWhenTargetNotAllowed` | 聊天流式 | `stream.hasNext()` 抛 `ModelException` | `TARGET_NOT_ALLOWED` | 1 | **0** |

"请求根本没离开进程"由 `assertThat(gateway.requestCount()).isZero()` 断言——不是只看异常类型。
"守卫确实在请求期做了判定"由 `attempts==1` 断言。

同用例还断言：拒绝**不可重试**
（`SpringAiModelClient.isRetryable(TARGET_NOT_ALLOWED) == false`），
异常消息不含 `sk-guard-test`、不含 `127.0.0.1`，
文案固定为 `"出站请求被受控边界拒绝"`（不区分"主机不在清单"与"解析到私网"，不回带目标地址）。

### 5.2 适配器级反向（主机/端口不在允许清单，请求期拒绝）

这类请求在创建期根本不会被工厂看见，所以只能用适配器直接构造。
策略 `allowedHosts=[api.example.com]`、`allowedPorts=[443]`、私网未批准：

| 用例 | 请求 | 守卫原因 |
|---|---|---|
| `blockingChannelRefusesHostOutsideAllowlistPerRequest` | `GET https://not-allowed.example.com/v1/chat/completions` | `TARGET_NOT_ALLOWED` |
| `blockingChannelRefusesPortOutsideAllowlistPerRequest` | `GET https://api.example.com:8443/v1/embeddings` | `TARGET_NOT_ALLOWED` |
| `blockingChannelRefusesCredentialHeaderByGuardRule` | `GET https://api.example.com/...` + `Cookie` 头 | `INVALID_REQUEST`（F09 请求卫生） |
| `reactiveChannelRefusesHostOutsideAllowlistPerRequest` | `GET https://not-allowed.example.com/...`（WebClient） | `TARGET_NOT_ALLOWED` |
| `reactiveChannelRefusesPlainHttpWhenPrivateTargetsNotApproved` | `POST http://api.example.com/...`（WebClient） | `TARGET_NOT_ALLOWED`（协议约束） |

拒绝发生在 DNS 解析与建连之前，因此这些用例**不产生任何外网流量**。

### 5.3 拒绝不被重试放大

厂商模型默认重试模板是 10 次指数退避（`RetryUtils.DEFAULT_RETRY_TEMPLATE`），
若不干预，被拒目标会被重发 10 次、退避累计约 76 秒，并把"被出站策略拒绝"包装成
`NonTransientAiException`（稳定码会退化成 `UPSTREAM_REJECTED`）。

因此四条通道一律显式传入**单次尝试** `RetryTemplate`（与 M02 对音频通道的既有理由一致：
厂商重试与平台受管重试 `AiModelProperties.maxAttempts` 叠加会放大上游压力）。
断言方式：`attempts==1`（守卫被判定一次）+ 上游 5xx 用例里
`gateway.requestCount()==maxAttempts`（重试次数只由平台预算决定）。

## 6. 非回退：端点停用 / 超时 / 5xx 重试语义

M02 既有断言**零删除**，`SpringAiEndpointIsolationTest` 用例数 11→11 未变。

| 既有语义 | 位置 | 状态 |
|---|---|---|
| 端点停用即解析层拒绝 | `SpringAiModelClientFactoryTest` | 未动，跑通 |
| 允许清单外的 baseUrl 创建期拒绝 | `SpringAiEndpointIsolationTest` / `CoverageBranchesTest` | 未动，跑通 |
| 凭据与模型标识隔离 | `SpringAiEndpointIsolationTest` | 未动，跑通 |
| 受管重试预算与稳定原因 | `SpringAiModelRetryTest` | 未动，跑通 |
| 超时稳定码 | `SpringAiModelRetryTest` | 未动，跑通 |

本卡新增两条非回退用例（走真实受控传输）：

| 用例 | 断言 |
|---|---|
| `upstreamTimeoutStillSurfacesAsRetryableTimeout` | 上游延迟 1.5s、`readTimeout=300ms` ⇒ 原因 `TIMEOUT`；`attempts==2`（仍按平台 `maxAttempts=2` 重发）；`isRetryable(TIMEOUT)==true` |
| `upstreamServerErrorStillRetriesWithinPlatformBudget` | 上游恒 500 ⇒ 原因 `RATE_LIMITED`（既有映射）；`gateway.requestCount()==2` 且 `attempts==2`（平台重试生效、厂商层不叠加重发）；消息不含 `sk-must-not-leak`、`upstream boom`、端点凭据 |

## 7. 发现的缺陷

### 缺陷 1（真实功能缺陷，本卡修复）：转写通道从未真正发出请求

`SpringAiModelClientFactory.createTranscriptionModel` 只设置了 `model`，
`OpenAiAudioTranscriptionOptions.responseFormat` 留空；厂商模型的便捷构造器会自己填
`JSON`，而这里显式构造选项绕过了它。

- 证据（用未接守卫的默认传输复现，证明与守卫接线无关）：
  `java.lang.IllegalArgumentException: response_format must not be null`，
  在**发出任何请求之前**抛出（夹具端点收到的请求数为 0，守卫调用数也为 0）。
- 影响：`ModelCapability.SPEECH_TO_TEXT` 端点的转写通道 100% 失败并收敛为 `UPSTREAM_FAILED`。
  既有测试只用过打桩 `TranscriptionModel`，所以没暴露。
- 修法：`provider/springai/SpringAiModelClientFactory.java` 显式给
  `.responseFormat(OpenAiAudioApi.TranscriptResponseFormat.TEXT)`。
- 为什么本卡可以改：在允许范围内（`provider/springai`），且不修就无法对第 3 条通道做逐条断言。

### 缺陷 2（接入陷阱，本卡修复）：`ClientHttpRequest.getAttributes()` 必须可变

`DefaultRestClient$DefaultRequestBodyUriSpec.exchangeInternal`（spring-web 6.2.19 字节码
偏移 571 附近）会执行 `request.getAttributes().putAll(attributes)`。
适配器首版返回 `Map.of()`（不可变），导致全部阻塞通道以
`UnsupportedOperationException` 失败。改为返回可变 `LinkedHashMap`。

### 缺陷 3（既有测试夹具需显式声明，本卡修改）：环回夹具端点不再是"默认可信"

M02 的 `SpringAiEndpointIsolationTest` 用 `http://127.0.0.1:<port>` 假端点且未设
`allowPrivateTargets`。请求接入守卫后这类端点会被判为环回未批准而拒绝。
已在两处夹具配置补 `properties.setAllowPrivateTargets(true)`
（这正是 `allowPrivateTargets` 的设计用途，F09 自身用例也这么用），
**断言未删减、未放宽，用例数 11→11**。

### 缺陷 4（**已由 F11 解决**）：流式响应的增量语义

守卫原本是请求/响应边界（返回受 `max-response-bytes` 约束的字节数组，不做流式），
因此响应式通道也只能"先读完整个 SSE 响应再交给 Reactor"——**流式不再是增量到达**，
且超长流会以 `RESPONSE_TOO_LARGE` **整体拒绝**（非截断）。

**已由 [F11](../../tasks/F11.md) 交付解决（commit `07c5e43`）**：
守卫新增 `openStream` 流式入口（与既有请求/响应入口**并存而非替换**，两者共用同一个
`prepare` 闸门链），逐块下发，超限改为**有界终止**并给稳定错误码。

增量到达的证据（对照用例只把守卫换成"整包才交付"的替身，因此断言不可能恒真）：

| | 首块到达时上游已写 | 首块时刻 | 间隔 |
|---|---|---|---|
| F11 流式入口 | 343 / 357 字节（未写完） | 262ms | **252ms** |
| 对照：整包交付 | 357 / 357（已写完） | 3699ms | 4ms |


## 8. 未验证项

1. **`OpenAiAudioApi.stream`（音频流式）无专项用例**。它与聊天流式共用同一个受控
   `WebClient` 实例（接线已生效），但平台当前没有调用它的运行期入口，故未逐条断言。
2. ~~**流式响应的增量到达行为未验证**~~ → **已由 F11 解决**：守卫新增 `openStream` 流式入口，
   聊天流式与音频流式恢复真正的增量到达（见缺陷 4 的对照数据）；长流超限改为**有界终止**并给
   `RESPONSE_TOO_LARGE`，已发出的增量不被回收为完整结果。
   **残留观察（未改）**：两个入口用同一个 `HttpRequest.timeout()` 与同一个 JDK 流，实测
   `read-timeout` **只约束响应头阶段**（readTimeout=500ms + 2s 中途停顿 → 阻塞 1926ms 后正常
   返回、无 `TIMEOUT`）。这与 F11 之前语义一致、**非回归**；流中途停顿仍由 M03 的
   `stream-idle-timeout` 兜底。若产品要求单块读取也受 `read-timeout` 约束，需另开卡（改超时语义）。
3. **真实厂商端点未验证**。全部用例打在本机假端点上（`AT-001`/`AT-004` 同样如此）。
4. **`ModelRequest.timeout()` 仍未下发给传输层**（M02 既有状态，本卡未改）。
   请求超时只由 `AiHttpProperties.readTimeout` / `connectTimeout` 决定。
5. **真实 MySQL/Redis Testcontainers IT 未新增**。本卡的治理点在传输边界（纯 IO 层，
   不碰持久化），需要真实数据库才能验证的链路不受影响；已有 IT 的端点配置
   （如 `AiModelProbePersistenceIT` 的清单外 baseUrl 探测失败结论）依赖创建期校验，
   该路径未变。**因此"请求期治理在完整平台上下文中的表现"未经 IT 验证**。
6. **未跑门禁与棘轮脚本**（按本卡铁律）。覆盖率数字来自 starter-ai 模块自身
   `jacoco:report`，不是棘轮读的聚合报告；**棘轮基线未更新**。
7. **跨模块影响未验证**：`module-ai` 与 server 的测试未在本卡执行（`ModelProtocolUpgradeRegressionTest`
   只做 JSON 解析、不建客户端，静态检查无影响）。全量门禁需主会话执行。

## 9. 命令与退出码

```sh
cd /home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot
S=basic-framework-core/basic-framework-spring-boot-starter-ai
export PATH=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH
```

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -q -o -pl $S spotless:apply` | 0 | 格式化通过 |
| `./mvnw -o -pl $S test-compile -q` | 0 | 编译通过 |
| `./mvnw -o -pl $S test -Dtest='GuardedExternalHttpTransportTest,OutboundGovernanceChannelTest,SpringAiEndpointIsolationTest' -DfailIfNoTests=false` | 0 | **33 例通过**（18 + 13 + 2），失败 0 |
| `./mvnw -o -pl $S test` | 0 | **295 例通过，失败 0，错误 0，跳过 0**（M02 基线 264 例 ⇒ 新增 31 例） |
| `./mvnw -o -pl $S jacoco:report` | 0 | 生成模块报告 |
| `javap -cp "$CP" 'org.springframework.ai.openai.api.OpenAiApi$Builder'` 等 4 条 | 0 | 见 §2 |

专项计数：`GuardedExternalHttpTransportTest` 18 例、`OutboundGovernanceChannelTest` 13 例、
`SpringAiEndpointIsolationTest` 2 例。
## 10. 覆盖率自查（sourcefile 逐文件）

脚本口径同卡片（读 starter-ai 模块自身 `target/site/jacoco/jacoco.xml`）：

| sourcefile | 行覆盖率 | 未覆盖行 |
|---|---|---|
| `GuardedExternalHttpTransport.java` | **100.00%** | — |
| `GuardedClientHttpRequestFactory.java` | **100.00%** | — |
| `GuardedExchangeFunction.java` | **100.00%** | — |
| `SpringAiModelClientFactory.java` | **100.00%** | — |
| `BasicFrameworkAiAutoConfiguration.java` | **100.00%** | — |
| `SpringAiModelClient.java` | **98.94%** | 283, 284, 585（**M02 既有代码，本卡未改**；M02 登记时为 97.7%） |

全模块**低于 80% 的 sourcefile 数量：0**（最低为 `RealtimeEvent.java` 82.61%，非本卡文件）。

补测过的地方（都是容易漏的分支）：传输层头剥离 vs 凭据头透传、空取值头归一、
拒绝原因沿厂商包装链查找、因果链超深的有界查找、关闭守卫后由自建/注入区分关闭责任、
响应视图在未知状态码与空响应体下的退化行为、响应式请求体的单块与分块写入路径。

## 11. 范围声明

- **允许范围**：只改了 `provider/springai`、`config`、`core/model` 中的
  `SpringAiModelClientFactory` / `SpringAiModelClient`，外加这三个包对应的 `src/test`。
  新增文件全部落在 `provider/springai` 与其测试目录内。**未越界。**
- **未改动 F09 守卫**：`core/http/`（`GuardedExternalHttpClient`、`ExternalHttpRequest`、
  `ExternalHttpResponse`、`ExternalHttpException`、`AiHttpProperties`）一个字节都没改，
  适配器按其既有契约编写。
- **未改动 M02 既有语义**：创建期允许清单校验、解析层停用端点拒绝、客户端缓存与 LRU 淘汰、
  凭据/模型标识隔离、受管重试预算与稳定原因映射全部保留；`SpringAiEndpointIsolationTest`
  断言零删除、零放宽。新增的只是"厂商重试模板统一为单次尝试"（与 M02 对音频通道的既有理由
  同源，是去掉叠加放大而不是削弱平台重试）与转写选项补 `responseFormat`。
- **未改动**：父 POM、依赖版本、契约台账 `version` 字段、前端、`E:\kuangjia\2026-main`。
- **未执行**：门禁脚本、`check-coverage-ratchet.mjs` 任何子命令、任何 git 写操作。
