# basic-framework-spring-boot-starter-ai

AI 能力接缝：向业务模块发布自有模型契约，并集中管理厂商接入。本 starter 是仓库内唯一允许引用
Spring AI 的位置，业务代码只消费自有契约，不感知厂商类型。

## 能力

| 契约 | 位置 | 说明 |
| --- | --- | --- |
| `ModelPort` | `com.basicframework.framework.ai.core.model` | 模型提供方端口：`capabilities`、`generate`、`stream`、`generateStructured`、`embed`、`probe`，以及媒体方法 `understandImage`/`recognizeImageText`/`generateImage`/`editImage`/`transcribeSpeech`/`synthesizeSpeech` |
| `ModelCapability` | `com.basicframework.framework.ai.core.model` | 能力词汇（`TEXT`、`TEXT_STREAM`、`STRUCTURED_OUTPUT`、`TOOL_CALLING`、`EMBEDDING`，X01 追加 `IMAGE_UNDERSTANDING`、`IMAGE_OCR`、`IMAGE_GENERATION`、`IMAGE_EDIT`、`SPEECH_TO_TEXT`、`TEXT_TO_SPEECH`）；`isMedia()` 标记媒体能力，`probeKind()` 给出 1:1 探测项 |
| 媒体契约 | `com.basicframework.framework.ai.core.model.media` | 平台自有媒体请求/响应：`MediaFileRef`（私有文件引用）、`MediaArtifact`（字节产物 + 摘要 + 尺寸/时长）、图片理解/OCR/生成/编辑与 STT/TTS 的请求响应类型；不含厂商类型与临时 URL |
| `ModelRequest` / `ModelResponse` | `com.basicframework.framework.ai.core.model` | 调用与结果：`ModelUsage` 区分"上游未提供（UNKNOWN）"与真实计量；工具调用只作为数据 |
| `ModelStream` / `ModelEvent` | `com.basicframework.framework.ai.core.model` | 文本流事件（`DELTA`、`TOOL_CALL`、`COMPLETED`），失败以 `ModelException` 抛出 |
| `StructuredModelRequest` / `StructuredModelResult` | `com.basicframework.framework.ai.core.model` | 结构化输出：要求单个 JSON 对象，平台校验后才交业务 |
| `StructuredJsonOutput` | `com.basicframework.framework.ai.core.model` | 结构化输出的有界修复与校验（围栏、前后文字、末尾逗号） |
| `AiProperties` | `com.basicframework.framework.ai.config` | 接缝配置：`enabled` 与 `capabilities` |
| `AiModelProperties` | `com.basicframework.framework.ai.config` | 调用护栏：重试次数、输出上限、流式超时与结构化修复步数 |
| `provider.springai` | `com.basicframework.framework.ai.provider.springai` | 唯一允许引用 `org.springframework.ai` 的区域 |
| `provider.realtime` | `com.basicframework.framework.ai.provider.realtime` | 实时协议验证判据（必需能力、协议一致性、音频格式范围）；**不含真实供应商适配器**——本环境没有实时凭据与出网通道，接入前不得声称真实链路已验证（ADR 0052 未验证项） |
| 实时语音契约 | `com.basicframework.framework.ai.core.realtime` | 协议与能力词汇（`RealtimeProtocol`/`RealtimeCapability`）、会话事实（`RealtimeSessionOpenRequest`/`RealtimeAudioFormat`）、事件与结束原因（`RealtimeEvent`/`RealtimeCloseReason`）、适配器接缝（`RealtimeAdapter`/`RealtimeCapabilityProbe`/`RealtimeSessionChannel`）与平台不变量模型（`RealtimeTurnFence`/`RealtimeBackpressure`）；实时能力不按供应商推断，逐（端点, 配置版本, 协议）声明 + 真实探测确认，未确认不可用（ADR 0052） |

## 启用条件与默认行为

默认**不启用**：未设置 `basic-framework.ai.enabled=true` 时不注册任何 Bean，应用照常启动。

启用后必须同时声明能力需求，且装配期必须满足以下全部条件，否则**启动失败**（fail-closed，
不留到首次请求）：

```yaml
basic-framework.ai:
  enabled: true
  capabilities: TEXT,EMBEDDING   # 平台声明需要的能力，不能为空
```

1. 恰好存在一个 `ModelPort` 实现（零个或多个都失败，错误消息列出实际实现类名）；
2. 该实现声明的能力集合非空；
3. 该实现覆盖 `capabilities` 声明的全部能力，缺失时错误消息列出缺失项。

启动校验器由 `BasicFrameworkAiAutoConfiguration` 注册，bean 初始化即完成校验，失败中止上下文刷新。

## 受控出站 HTTP 边界

启用 AI 能力时自动注册 `ExternalHttpClient`（实现 `GuardedExternalHttpClient`），所有外部调用必须经由它，
不得自建 `RestTemplate`/`HttpClient`。**默认拒绝一切目标**：必须显式配置允许清单才可出站。

```yaml
basic-framework.ai:
  enabled: true
  capabilities: TEXT
  http:
    allowed-hosts: [api.openai.com]     # 精确主机名；为空表示拒绝一切
    allowed-ports: [443]                 # 默认仅 443
    allow-private-targets: false         # 企业内网目标必须显式开启
    connect-timeout: 5s
    read-timeout: 30s
    max-response-bytes: 1048576
    max-header-count: 32
```

策略细节、传输行为（不跟随重定向、TLS 默认校验、响应上限、取消与关闭）与残余风险见
[docs/security/outbound-http-boundary.md](../../../../../docs/security/outbound-http-boundary.md)。

## 模型调用护栏（M03）

调用层约束全部有保守默认值，越界配置在启动期失败；端点还需在能力集合中声明对应能力，
否则调用前即得到 `CAPABILITY_UNSUPPORTED`（消息指明缺失的能力名）。

```yaml
basic-framework.ai:
  model:
    max-attempts: 3                    # 含首次；只对超时/限流/上游失败重发
    retry-backoff: 200ms
    max-output-chars: 262144           # 文本与工具调用参数合计上限，超出中断并释放连接
    stream-idle-timeout: 30s           # 事件间空闲超时，超时判 TIMEOUT 并关闭流
    stream-queue-capacity: 64          # 有界队列：消费慢时形成背压，不无限占用内存
    max-repair-steps: 3                # 结构化输出的修复步数上限，0 表示不修复
```

- **有界重试**：只重发 `TIMEOUT`、`RATE_LIMITED`、`UPSTREAM_FAILED`；上游明确拒绝
  （`UPSTREAM_REJECTED`）、能力缺失、输入非法与输出越界都不重发。流式调用不重发（避免重复增量）。
- **用量语义**：上游缺失 usage 时记 `ModelUsage.UNKNOWN`，展示为 UNKNOWN/ESTIMATED，**不得写成 0**
  （AT-060）；`estimated=true` 的估算值不参与真实计量。
- **结构化输出**：平台把 JSON Schema 作为输出契约嵌入提示词，并对模型输出做**有界修复**
  （去 Markdown 围栏 → 截取最外层对象 → 去末尾逗号，只做确定性变换、不猜测字段），
  修复后仍不可解析或不是 JSON 对象时给出 `INVALID_STRUCTURED_OUTPUT`，不自动重问上游。
  字段级 Schema 校验属于调用方的领域契约（`docs/contracts/ai` 下的 Schema）。
- **工具不自动执行**：接缝不注册任何工具回调，`TOOL_CALL` 事件与 `ModelResponse.toolCalls()`
  只是数据；是否执行、如何授权由平台工具层决定（D08/D09）。
- **流关闭释放连接**：`ModelStream.close()` 取消上游订阅、丢弃未消费事件，重复调用幂等；
  空闲超时、输出越界与上游错误都会先释放连接再抛稳定错误。

## 多模态媒体契约（X01）

图片理解/OCR/生成/编辑与非实时 STT/TTS 按同一 `ModelCapability` 枚举扩展，能力与探测项 1:1：

- **默认拒绝**：`ModelPort` 的六个媒体方法都有默认实现，未覆盖时抛
  `ModelException.Reason.CAPABILITY_NOT_ENABLED`（消息只含能力名），**不静默降级为文本调用**、
  不自动切换端点或供应商。
- **平台自有类型**：请求/响应只含平台语义（私有文件 `fileId` 引用、字节产物、用量），
  构造即校验 MIME 形状、SHA-256 自洽、图片尺寸 1-8192、张数 1-8、格式白名单、
  TTS 文本上限 4096；厂商 multipart/base64/异步轮询差异只允许出现在 `provider.springai`。
- **产物接管**：适配器只返回 `MediaArtifact` 字节；落私有文件、生成 fileId 引用由调用方完成，
  不返回厂商临时地址（FR-35）。
- **准入在三道校验之后**：端点启用 → 能力已声明 → 该能力探测结论 `SUPPORTED` 且配置版本一致；
  未开通时在解析客户端与任何网络请求之前拒绝（准入闸门在 `module-ai` 的
  `AiMediaCapabilityGate`，AT-068）。准入/输入输出错误码见
  [error-code-map](../../../../docs/contracts/ai/error-code-map.md)，逐能力声明字段与实测状态见
  [多模态端点准入矩阵](../../../../docs/integrations/ai-platform-multimodal-endpoints.md)。
- **实时语音不在本契约内**：FR-37 的会话协商与短期凭证需要独立 ADR（V1.2），不预留占位枚举。
- **适配器覆盖状态（X04）**：`provider.springai` 的 `SpringAiModelClient` 已实现
  `SPEECH_TO_TEXT` / `TEXT_TO_SPEECH` 两个**真实调用探测**（平台合成 WAV 夹具 / 最短文本；
  空文本记 `NO_TEXT_RETURNED`、空音频记 `NO_AUDIO_RETURNED`）与 `synthesizeSpeech` 运行期调用
  （产物 MIME 由请求输出格式固定映射为 audio/mpeg、audio/wav、audio/ogg，用量缺失记 `UNKNOWN`，
  不伪造上游计量）。`transcribeSpeech` 的运行期**未实现**：端口契约要求按 `fileId` 取私有文件字节，
  而 starter 内没有"文件编号 → 字节"的受管接缝，因此保持平台默认的"能力未开通"明确拒绝
  （不伪造成功）；该缺口与图片媒体的运行期适配一并登记在
  [多模态端点准入矩阵](../../../../docs/integrations/ai-platform-multimodal-endpoints.md) 的未验证清单里。
  图片生成/编辑探测仍为"适配器未实现"，X03/X04 的运行期调用由任务执行器 + 端点声明承接。

## 约束

- **厂商类型不出接缝**：`org.springframework.ai` 类型只能出现在 `provider.springai` 包内，
  其他包引用会被 `ModuleBoundaryArchitectureTest` 的 vendor 规则拒绝；自有契约不得暴露厂商类型。
- **配置即契约**：不允许在业务代码里散落默认值或硬编码模型参数；新增可变配置必须先落到
  `AiProperties` / `AiModelProperties` 并有校验语义。
- **错误信息不含敏感内容**：启动校验消息只包含类名与能力名，不含端点、凭据或模型输入；
  `ModelException` 消息不含厂商原始报文。
- **仓库内消费点**：`module-ai` 的 `AiModelClientResolver` 按启用端点与当前版本解析受管客户端，
  并通过工厂的 `invalidate` 在改配置/轮换/停用后关闭旧客户端；接缝的其他能力仍按
  [ADR 0028](../../../../docs/adr/0028-framework-seams-may-have-zero-in-repo-consumers.md)
  由本模块契约测试钉住，不在能力目录中宣称"已被生产验证"。

## 测试

```sh
cd 后端代码/basic-framework-boot
./mvnw -pl basic-framework-core/basic-framework-spring-boot-starter-ai test
```

- 装配：`BasicFrameworkAiAutoConfigurationTest` 覆盖默认关闭、启用后缺能力声明、缺提供方、
  提供方能力为空、能力不足、多实现六种装配结果。
- 护栏：`AiModelPropertiesTest` 覆盖默认值、覆盖值与越界值启动失败。
- 契约：`ModelContractTest`、`ModelEventTest`、`ModelPortDefaultsTest`、`StructuredJsonOutputTest`、
  `MultimodalVocabularyTest`、`MediaPortDefaultsTest`、`MediaFileRefTest`、`MediaArtifactTest`、
  `ImageMediaContractTest`、`SpeechMediaContractTest`。
- 调用：`SpringAiModelClientCoverageTest`、`SpringAiModelStreamTest`、`SpringAiModelRetryTest`、
  `SpringAiStructuredOutputTest`、`SpringAiEndpointIsolationTest`、`SpringAiMediaProbeTest`、
  `SpringAiSpeechTest`。
