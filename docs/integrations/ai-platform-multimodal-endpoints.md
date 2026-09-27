# 多模态端点准入矩阵与供应商适配准入结论（X01）

本文件是 [X01 扩展多模态能力契约与端点验证矩阵](../ai-platform/tasks/X01.md) 的交付物：
冻结图片理解/OCR/生成/编辑与非实时 STT/TTS 的**能力标识、需要声明的字段、探测项、准入判据、
不可用时的明确拒绝**，并逐项登记**端点实测状态**（本环境无出网、无真实供应商凭据，
因此全部实测项标记"未验证"，复现步骤见 §6；不得把未执行过的探测填成已验证）。

配套材料：

- 契约代码：`starter-ai` 的 `com.basicframework.framework.ai.core.model`（`ModelCapability`、`ModelProbeKind`、
  `ModelPort`、`ModelException`）与 `com.basicframework.framework.ai.core.model.media`（媒体请求/响应值对象）；
- 拒绝链路与错误码：[错误码映射](../contracts/ai/error-code-map.md)、`AiMediaCapabilityGate`（`module-ai`）；
- 上游依赖冻结：[上游候选台账](ai-platform-upstream-candidates.md)（Spring AI 1.1.8，Boot 3.5 线）；
- 执行记录与命令退出码：[x01 证据](../ai-platform/verification/x01-multimodal-capability-contract-evidence.md)。

**硬规则（FR-35、AT-068）**：兼容文本 API 可用 **不能**推断媒体能力可用；每个能力单独声明、单独探测；
未声明或未探测确认时，媒体调用必须在任何网络请求之前**明确拒绝**（无隐藏外发），
不得自动切换或回退到其它模型、端点或供应商（故障转移也要显式配置，不在本契约内）。

## 1. 能力与探测词汇（冻结）

| 能力标识（`ModelCapability`） | 端口方法（`ModelPort`） | 探测项（`ModelProbeKind`） | 输入 | 输出 | 任务模式 |
|---|---|---|---|---|---|
| `IMAGE_UNDERSTANDING` | `understandImage` | `IMAGE_UNDERSTANDING` | 私有图片引用 + 理解指令 | 文本 | 同步 |
| `IMAGE_OCR` | `recognizeImageText` | `IMAGE_OCR` | 私有图片引用 + 可选语言提示 | 文本 | 同步 |
| `IMAGE_GENERATION` | `generateImage` | `IMAGE_GENERATION` | 提示词 + 可选尺寸/张数/格式 | 1-8 个图片产物 | 同步 |
| `IMAGE_EDIT` | `editImage` | `IMAGE_EDIT` | 私有底图 + 编辑指令 + 可选尺寸/格式 | 1-8 个图片产物 | 同步 |
| `SPEECH_TO_TEXT` | `transcribeSpeech` | `SPEECH_TO_TEXT` | 私有音频引用 + 可选语言提示 | 全文 + 可选分段字幕 | 同步 |
| `TEXT_TO_SPEECH` | `synthesizeSpeech` | `TEXT_TO_SPEECH` | 文本 + 可选音色/格式 | 音频产物 | 同步 |

- 探测项与能力 **1:1 同名**（`ModelCapability.probeKind()`），调用方不得自行猜探测项；
  `CONNECTIVITY` 只证明可达，不对应任何可发布能力。
- 媒体探测必须使用平台内置的**最小合成夹具**（小尺寸图片、短音频），不得使用真实用户数据；
  探测结论只记录状态/明细码/耗时，不落输入内容与上游报文。
- **异步**：本切片只冻结同步契约。长音频/大图等异步任务由 X02+ 以运行任务（`ai_run_task`）接管，
  未实现前端点不得对外宣称支持异步；本卡不提供任何异步占位实现。
  上表"任务模式"指**端口调用形态**（六方法都是同步请求/响应，不含厂商轮询）；产品页面的任务化
  （持久运行任务、进度/取消/下载）由 X02–X04 在上层实现，不改变端口契约。跨语言包若按产品维度
  标注执行模式（例如把生成/编辑标为 `ASYNC`），必须与端口同步契约区分，不能被读成端点采用异步协议。

## 2. 每个能力需要声明的字段与准入判据

端点侧需要声明的字段（语义在本卡冻结；持久化形态随 X02 的端点扩展配置落地）：

> **生效范围（不得误读）**：本卡只冻结这些字段的**语义与平台硬上限**，没有为它们提供持久化位置
> 与端点级校验实现；目前在代码中强制生效的只有平台硬上限（`core.model.media` 构造即校验）
> 与"能力是否开通"的准入。端点级白名单/字节上限/时长上限在 X02 落地前**不得对外声称已生效**。

| 能力 | 端点必须声明的字段 | 平台硬上限（代码强制，端点只能更窄） |
|---|---|---|
| 图片理解 | 输入格式白名单（MIME）、单文件字节上限、最大边长/总像素 | MIME 形状合法；`fileId` 为正；摘要为 64 位十六进制 |
| OCR | 输入格式白名单、单文件上限、最大边长、支持语言列表 | 同上 |
| 图片生成 | 输出格式白名单、尺寸范围、单次张数上限 | 尺寸 `宽x高` 且每边 1-8192；张数 1-8；格式 png/jpeg/webp |
| 图片编辑 | 输入/输出格式白名单、底图单文件上限、尺寸范围 | 尺寸语法与上限同生成；输出格式同生成 |
| STT | 音频格式白名单、单文件上限、**时长上限**、支持语言、是否支持分段字幕 | MIME 形状合法；`MediaArtifact.durationMillis` 为正 |
| TTS | 音频输出格式白名单、文本长度上限、音色表、采样率 | 文本 1-4096 码元；格式 mp3/wav/opus |

准入判据（`AiMediaCapabilityGate` 顺序固定，全部在解析客户端之前）：

1. **端点可用**：存在且启用（不存在 404 / 停用 409）；
2. **能力已声明**：当前配置版本的能力集合包含该媒体能力（未声明 → 400 `1_003_002_007`）；
3. **探测已确认**：该能力探测的最新结论为 `SUPPORTED`，且结论的配置版本等于当前配置版本
   （未探测 / `FAILED` / `UNSUPPORTED` / 版本过期 → 400 `1_003_002_007`）；
4. 通过后才解析受管客户端并调用端口；端口的媒体失败按 §3 映射为平台错误码。

探测结论的有效性：**配置版本变化即失效**（必须重探）。凭据轮换当前不使结论失效
（探测结果 DTO 只暴露配置版本，见 §7 待办）；轮换后建议立即重探。

## 3. 不可用时的明确拒绝（AT-068 可验证部分）

| 场景 | `ModelException.Reason` | 平台错误码 | HTTP |
|---|---|---|---|
| 未声明 / 未探测确认 / 探测失败 / 结论过期 | `CAPABILITY_NOT_ENABLED` | `AI_MODEL_CAPABILITY_NOT_ENABLED`（1_003_002_007） | 400 |
| 请求不合规（尺寸/张数/格式/音色/文本长度） | `MEDIA_INPUT_INVALID` | `AI_MEDIA_REQUEST_INVALID`（1_003_010_000） | 400 |
| 输入媒体类型不在声明白名单 | `MEDIA_INPUT_TYPE_UNSUPPORTED` | `AI_MEDIA_INPUT_TYPE_UNSUPPORTED`（1_003_010_001） | 400 |
| 输入文件超过声明上限 | `MEDIA_INPUT_TOO_LARGE` | `AI_MEDIA_INPUT_TOO_LARGE`（1_003_010_002） | 400 |
| 音频时长超过声明上限 | `MEDIA_INPUT_DURATION_EXCEEDED` | `AI_MEDIA_INPUT_DURATION_EXCEEDED`（1_003_010_003） | 400 |
| 上游成功但无媒体产物 | `MEDIA_OUTPUT_EMPTY` | `AI_MEDIA_OUTPUT_EMPTY`（1_003_010_004） | 502 |
| 端口未实现媒体方法（默认实现） | `CAPABILITY_NOT_ENABLED` | 同上 1_003_002_007 | 400 |

- **零外发**由测试钉住：`AiMediaCapabilityGateTest` 用会计数的媒体端口断言准入失败时
  调用计数为 0、`AiModelClientResolver.resolve` 从未被调用；`MediaPortDefaultsTest` 断言默认方法
  不触发任何文本调用；`SpringAiModelClient` 对媒体探测项返回"适配器未实现"，不发起厂商调用。
- **产生方**：`CAPABILITY_NOT_ENABLED` 由准入闸门与端口默认实现产生（已实现、已测试）；
  `MEDIA_INPUT_*` / `MEDIA_OUTPUT_EMPTY` 的产生方是 X02+ 的媒体校验与适配层，本卡只实现并测试
  它们在闸门处的**映射**（Reason → 平台错误码，不回传上游文案），不提供产生这些 Reason 的占位实现。
- 拒绝响应只含平台文案：不返回上游报文、输入内容、提示词或凭据。

## 4. 端点实测矩阵（逐项状态）

候选协议按"OpenAI 兼容"族的常见形态列出；**实测结论必须逐项做真实调用**，不能用文本探测代替。
本环境（无出网、无供应商凭据）**未执行任何一项**，状态一律为"未验证"：

| 探测项 | 计划协议（候选） | 探测判据（SUPPORTED 条件） | 实测状态 |
|---|---|---|---|
| `IMAGE_UNDERSTANDING` | `POST {baseUrl}/chat/completions`，消息内容含 `image_url`（base64 数据或受控 URL） | HTTP 2xx 且返回非空文本 | **未验证** |
| `IMAGE_OCR` | 同上，指令要求"提取图片文字"，可用仅含文字的合成图片 | HTTP 2xx 且返回非空文本；仅当模型拒绝图片内容时为 `UNSUPPORTED` | **未验证** |
| `IMAGE_GENERATION` | `POST {baseUrl}/images/generations` | HTTP 2xx 且返回至少 1 个非空图片产物 | **未验证** |
| `IMAGE_EDIT` | `POST {baseUrl}/images/edits`（multipart：图片 + 指令） | HTTP 2xx 且返回至少 1 个非空图片产物 | **未验证** |
| `SPEECH_TO_TEXT` | `POST {baseUrl}/audio/transcriptions`（multipart：短音频） | HTTP 2xx 且返回非空文本 | **未验证** |
| `TEXT_TO_SPEECH` | `POST {baseUrl}/audio/speech` | HTTP 2xx 且返回非空音频字节 | **未验证** |

"未验证"的原因：本机无出网、无真实供应商凭据；X02–X04 的实现面（`provider.springai` 媒体方法）
尚未实现，探测项当前只返回"适配器未实现"的稳定结论；且现行探测流程的探测顺序不含媒体项（见 §7「X02 必须先补的准入前置」），
媒体探测目前不会被执行。上述三项都不构成"已验证 SUPPORTED"。

## 5. 输出文件接管、计量与不可用降级

- **产物接管**：适配器只返回 `MediaArtifact`（字节 + MIME + SHA-256 + 尺寸/时长）；
  调用方必须**先落平台私有文件**（`infra_file`，私有 purpose），再把 `fileId` 引用交给业务。
  不返回厂商临时下载地址、不把 URL 写入长期存储协议；生成结果仍是私有文件（FR-35）。
- **计量/费用**：媒体响应携带 `ModelUsage`，上游缺失时记 `UNKNOWN`（不伪造 0）；
  音频实际时长由 `MediaArtifact.durationMillis()` 提供，图片张数由 `ImageResult.images().size()` 提供。
  现有点量账本按 token/字符估算，**媒体计价维度（张/秒）尚未落地**，见 §7。
- **不可用降级**：能力未开通 → 400 明确拒绝且零外发；FR-35 的"失败不自动外发到另一提供商"由
  "不做候选遍历 + 无隐式失败转移"保证（调用方显式指定端点）。
- **浏览器授权（FR-36）**：录音授权被拒时的页面降级（继续文本使用）属 X02+ 页面行为，
  本卡只冻结后端拒绝语义，不宣称页面已实现。

## 6. 未验证项与精确复现步骤

以下步骤在具备出网与凭据的实验环境执行；命令中的 `$BASE_URL`（必须与 `basic-framework.ai.http.allowed-hosts`
允许清单一致）、`$API_KEY`、`$MODEL` 由执行者提供，**不得写入仓库**。夹具必须是平台生成的合成内容。

```bash
# 0) 合成夹具（不提交二进制）
#    PNG：1x1 或 64x64 纯色图；WAV：0.5 秒 8kHz 静音/单音
#    可用 ImageMagick：convert -size 64x64 xc:white /tmp/x01-fixture.png
#    可用 ffmpeg：ffmpeg -f lavfi -i anullsrc=r=8000:cl=mono -t 0.5 /tmp/x01-fixture.wav

# 1) 图片理解（期望：JSON 文本非空）
curl -sS -o /tmp/x01-understanding.json -w '%{http_code}\n' \
  -X POST "$BASE_URL/chat/completions" \
  -H "Authorization: Bearer $API_KEY" -H 'Content-Type: application/json' \
  -d "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"描述这张图片\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,$(base64 -w0 /tmp/x01-fixture.png)\"}}]}]}"

# 2) OCR（期望：JSON 文本非空；只含文字的合成图更稳定）
#    同上，把 text 换成"提取图片中的文字"，替换夹具为含已知文字的合成图。

# 3) 图片生成（期望：data[0] 有非空 b64_json 或可下载 URL）
curl -sS -o /tmp/x01-generation.json -w '%{http_code}\n' \
  -X POST "$BASE_URL/images/generations" \
  -H "Authorization: Bearer $API_KEY" -H 'Content-Type: application/json' \
  -d "{\"model\":\"$MODEL\",\"prompt\":\"一张纯白 64x64 测试图\",\"size\":\"64x64\",\"n\":1}"

# 4) 图片编辑（期望：产物非空）
curl -sS -o /tmp/x01-edit.json -w '%{http_code}\n' \
  -X POST "$BASE_URL/images/edits" \
  -H "Authorization: Bearer $API_KEY" \
  -F "model=$MODEL" -F "prompt=把背景改为纯黑" -F "image=@/tmp/x01-fixture.png"

# 5) STT（期望：text 字段非空）
curl -sS -o /tmp/x01-stt.json -w '%{http_code}\n' \
  -X POST "$BASE_URL/audio/transcriptions" \
  -H "Authorization: Bearer $API_KEY" \
  -F "model=$MODEL" -F "file=@/tmp/x01-fixture.wav" -F "language=zh"

# 6) TTS（期望：响应体非空音频字节；-o 到文件后检查文件大小与魔数）
curl -sS -o /tmp/x01-tts.mp3 -w '%{http_code}\n' \
  -X POST "$BASE_URL/audio/speech" \
  -H "Authorization: Bearer $API_KEY" -H 'Content-Type: application/json' \
  -d "{\"model\":\"$MODEL\",\"input\":\"你好，这是合成测试。\",\"voice\":\"alloy\",\"response_format\":\"mp3\"}"
ls -l /tmp/x01-tts.mp3
```

验收判据（逐项独立）：HTTP 2xx **且**按上表判据命中；上游 4xx/5xx、空产物或协议不符记
`FAILED`/`UNSUPPORTED`，**不得**记为 SUPPORTED，也不得据此推断其它能力。

未验证清单（本卡交付时状态）：

1. 六个媒体能力在真实端点的探测结论（全部"未验证"，见 §4）；
2. `provider.springai` 的媒体实现与厂商重试/取消语义（X02–X04，未实现）；
3. 媒体计费维度（张/秒）与配额护栏（X02+ 与 Q02 计量扩展，未实现）；
4. 凭据轮换对探测结论的影响（DTO 未暴露凭据版本，未验证）。

## 7. 变更与消费者约定

- 词汇唯一来源：后端 `ModelCapability` / `ModelProbeKind`；前端 `data.ts` 的
  `CAPABILITY_OPTIONS` / `PROBE_KIND_LABELS` 必须与之一致（本卡已同步，含既有精确列表断言）。
- 存储：`ai_model_endpoint_revision.capabilities`、`ai_service.required_capabilities`、
  `ai_service_release.required_capabilities` 由 [V84 迁移](../../后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/V84__ai_multimodal_capability_widen.sql)
  加宽到 `varchar(255)`（11 个能力全量拼接 145 字符）；旧客户端按未知能力值**降级展示**，
  不认识的值不参与页面选择，不影响已有 `TEXT` 等取值语义。
- 后续媒体声明字段（§2）的持久化：X02 起以端点扩展配置落地，字段名与拒绝码沿用本文件；
  在此之前不得在业务代码里各自发明字段或校验。
- **X02 必须先补的准入前置（本卡未授权修改，已登记）**：`probeAll` 的探测顺序
  （`AiModelCapabilityProbeServiceImpl.PROBE_ORDER`）与探测项→能力映射（同文件 `KIND_CAPABILITIES`）
  目前只覆盖连接/文本/流式/结构化/工具/嵌入六项，**不含媒体探测项**，因此第 2 节的第 3 条判据
  （探测已确认）在现行探测流程下对媒体能力不可达。X02–X04 在 `provider` 实现媒体探测方法时，
  必须在同一次变更里把六个 `ModelProbeKind` 媒体项加入这两处并补探测服务测试；该文件不在 X02–X04
  卡片的允许路径内，需要主管先扩展对应卡片范围。本卡只保证"未确认即拒绝"的拒绝侧语义。
- 供应商适配准入结论（X01）：不新增依赖，媒体适配沿用冻结的 Spring AI 1.1.8 / Boot 3.5 线
  （见[上游候选台账](ai-platform-upstream-candidates.md)）；该版本已包含 Bedrock 多模态 URL SSRF
  修复（GHSA-mhrg-94vw-45c5，1.1.4 起）与 1.1 线全部已知公告修复，未发现需要新增媒体专用依赖的理由。
  厂商协议细节（multipart/base64/异步轮询）只允许出现在 `provider.springai`，不得进入本矩阵之外的公开契约。
- 新增媒体能力必须同时更新：本文件的 §1/§2/§4、`docs/contracts/ai/error-code-map.md`（如新增错误码）、
  starter README 与本矩阵的实测结论。
