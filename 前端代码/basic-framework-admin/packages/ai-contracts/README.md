# @vben/ai-contracts

AI 中台前端共享的**协议契约包**：用 zod 定义跨包、跨语言的稳定词汇与校验规则，并导出对应 TypeScript 类型。消费方（Chat UI、嵌入 SDK、管理端页面）只依赖这里的 schema 与类型，不各自猜字段、不各自放宽校验。

包内每个模块对应一份协议：

| 模块 | 协议 | 说明 |
| --- | --- | --- |
| `bridge.ts` | 嵌入桥（C06） | 宿主 ↔ iframe 的判别联合、白名单与版本协商 |
| `business-context.ts` | 业务上下文（C08） | 六个登记键的逐键形状；身份与范围字段不在协议内 |
| `chart-spec.ts` | ChartSpec | 自有图表契约，厂商类型不出适配器边界 |
| `result-block.ts` | ResultBlock | Chat 与报表共享的最小渲染单元 |
| `run-event.ts` | RunEvent | SSE 运行事件（`schemaVersion` 未知即拒绝） |
| `theme.ts` | Theme | 只含设计 token，不接受任意 CSS |
| `client/protocol.ts`、`client/sse.ts` | 开放 API 客户端 | CommonResult 信封、稳定错误、seq 去重、SSE 解析 |
| `multimodal.ts` | 多模态能力（X01） | 图片理解 / OCR / 图片生成 / 图片编辑 / STT / TTS 的结果、目录与不可用语义 |
| `multimodal-request.ts` | 多模态请求（X01） | 六类能力的请求参数约束（尺寸/格式/张数/时长/语言/音色） |

## 多模态能力契约（X01，FR-35 / FR-36）

### 三条不变量（对应 AT-068）

1. **能力标识显式**：请求带 `capability`、结果带 `kind`，两者必须一致；未登记能力在解析期拒绝，不允许把未知能力"尽力"映射到已有通道。
2. **结果只引用私有文件**：产物只以 `privateFileRefSchema`（中台文件编号 + 名称/媒体类型/大小/摘要）出现；契约里**没有任何** `url`/`uri`/`link`/`provider`/`endpoint`/`fallback` 字段，因此不存在 "上游直链外发"的数据形状。读取产物走受控文件接口并按当前授权再次判定。
3. **未开通即明确不可用**：`multimodalAvailabilitySchema` 用稳定 `reasonCode` + 十位数字 `errorCode` 表达不可用；`outcome: 'UNAVAILABLE'` 是终态，消费方必须原样展示，**不得**自动改用其它供应商、不得把请求重发到未授权通道。前端也不得指定 `modelId`/`endpointId`/供应商（未知字段会被拒绝）。

### 与后端 X01 冻结词汇的对应

| 契约项 | 后端锚点 | 取值 |
| --- | --- | --- |
| 能力标识 | `ModelCapability` 追加项 | `IMAGE_UNDERSTANDING`、`IMAGE_OCR`、`IMAGE_GENERATION`、`IMAGE_EDIT`、`SPEECH_TO_TEXT`、`TEXT_TO_SPEECH` |
| 图片单边上限 | `MediaValues.MAX_IMAGE_DIMENSION` | 1..8192 |
| 尺寸语法 | `MediaValues.normalizeImageSize` | `宽x高`，如 `1024x1024` |
| 生成张数 | `ImageGenerationRequest.MAX_COUNT` | 1..8 |
| 生成格式 | `ImageGenerationRequest.OUTPUT_FORMATS` | `png` / `jpeg` / `webp` |
| TTS 文本 | `SpeechSynthesisRequest.MAX_TEXT_LENGTH` | ≤4096 码元 |
| TTS 格式 | `SpeechSynthesisRequest.OUTPUT_FORMATS` | `mp3` / `wav` / `opus` |
| STT 分段 | `SpeechSegment` | `text` / `startMillis` / `endMillis` |
| 文件引用 | `MediaFileRef` | `fileId` / `mime` / `size` / 可选 `sha256` |
| 未开通 | `ModelException.Reason.CAPABILITY_NOT_ENABLED` | 任何网络请求之前拒绝，不回退 |
| 失败码 | `AiErrorCodeConstants` 1_003_010_xxx | 见下表 |

### 能力、结果与执行形态

| capability | 结果 kind | 执行形态 |
| --- | --- | --- |
| `IMAGE_UNDERSTANDING` | `image_understanding` | 同步 |
| `IMAGE_OCR` | `image_ocr` | 同步 |
| `IMAGE_GENERATION` | `image_generation` | 异步（持久任务受理返回 `ACCEPTED`） |
| `IMAGE_EDIT` | `image_edit` | 异步 |
| `SPEECH_TO_TEXT` | `speech_to_text` | 同步 |
| `TEXT_TO_SPEECH` | `text_to_speech` | 同步 |

映射由 `MULTIMODAL_CAPABILITIES`、`MULTIMODAL_RESULT_KINDS`、`MULTIMODAL_CAPABILITY_EXECUTION` 固定；`parseMultimodalResponse` 会拒绝"声明 A 能力却返回 B 结果"或"同步能力返回受理态"。

### 应用层约束（`MULTIMODAL_LIMITS`，全部是拒绝线）

| 维度 | 取值 |
| --- | --- |
| 图片格式 | `image/jpeg`、`image/png`、`image/webp`（不接受 SVG/动图） |
| 图片字节 | 单张 ≤ 10 MiB |
| 音频格式 | `audio/mpeg`、`audio/mp4`、`audio/ogg`、`audio/wav`、`audio/webm` |
| 音频字节/时长 | ≤ 25 MiB；非实时语音单段 ≤ 300 秒 |
| 指令/提示词 | 理解 ≤ 4000 字符；生成 ≤ 2000；编辑 ≤ 2000 |
| 语言 | `en-US`、`ja-JP`、`zh-CN`、`zh-TW`（`MULTIMODAL_LANGUAGES`） |
| 音色 | `voiceId` 必须是能力目录登记过的标识；目录见下 |
| OCR 区域 / STT 分段 | ≤ 1000 区域 / ≤ 2000 分段 |

端点声明的准入矩阵可以更窄；本契约的数值与端点声明不一致时，服务端按更窄的执行，前端必须按目录/服务端错误明确拒绝，不得替用户放宽。

### 能力目录与音色白名单

`multimodalCapabilityDescriptorSchema` 描述"某服务下某能力是否可用、怎么执行、有哪些语言/音色"：

- `availability.state === 'AVAILABLE'` 才允许发起调用；
- 可用的 TTS 必须在 `voices` 里登记音色白名单（客户端据此渲染选项，不硬编码供应商音色名）；
- 非 TTS 能力不得携带 `voices`；未知字段一律拒绝；
- 发 TTS 请求前过两道门禁：`isCapabilityAvailable(availability)` 判能力此刻是否可用、 `isVoiceRegistered(descriptor, voiceId)` 判音色是否登记（大小写敏感，未登记一律 false）；任一不过都必须明确拒绝，不得改猜供应商音色名"试发"。

### 不可用语义与错误码

`MULTIMODAL_UNAVAILABLE_REASONS` → `MULTIMODAL_UNAVAILABLE_ERROR_CODES`（十位数字，复用 `docs/contracts/ai/error-code-map.md` 与 X01 已加编号）：

| reasonCode | 含义 | errorCode |
| --- | --- | --- |
| `CAPABILITY_NOT_ENABLED` | 端点未声明该能力，或声明了但未通过探测确认 | `1003002007` |
| `ENDPOINT_DISABLED` | 端点已停用 | `1003002001` |
| `OUTBOUND_BLOCKED` | 资源等级不允许外发（策略先于网络调用） | `1003002006` |
| `SERVICE_RESOURCE_UNAVAILABLE` | 发布版本依赖的资源绑定不可用 | `1003008004` |

媒体请求/输出的失败码（`MULTIMODAL_ERROR_CODES`，键名与错误码表的 `AI_MEDIA_*` 常量一一对应）： `MEDIA_REQUEST_INVALID` `1003010000`、`MEDIA_INPUT_TYPE_UNSUPPORTED` `1003010001`、 `MEDIA_INPUT_TOO_LARGE` `1003010002`、`MEDIA_INPUT_DURATION_EXCEEDED` `1003010003`、 `MEDIA_OUTPUT_EMPTY` `1003010004`。

### 版本与兼容

- 请求与响应都带 `schemaVersion`（当前 `1.0`），未知版本直接拒绝；
- 全部对象都是 `.strict()`：未知字段被拒绝，不会"去掉坏字段继续用"；
- 新增能力或字段遵循 N/N-1：先改本包 schema 与样例，再改消费者；三个多模态测试文件（`multimodal.test.ts`、`multimodal-capability.test.ts`、`multimodal-guard.test.ts`）冻结了能力清单、白名单、上限、错误码映射与"任何嵌套层级都没有 URL/供应商/降级字段"不变式，任何一处改动都必须同步改测试。

### 用法

```ts
import {
  isCapabilityAvailable,
  parseMultimodalCapabilityDescriptor,
  parseMultimodalRequest,
  parseMultimodalResponse,
} from '@vben/ai-contracts';

// 1. 先看能力目录：不可用就明确拒绝，不尝试别的供应商
const descriptor = parseMultimodalCapabilityDescriptor({
  availability: {
    errorCode: '1003002007',
    message: '当前服务未开通图片理解能力',
    reasonCode: 'CAPABILITY_NOT_ENABLED',
    state: 'UNAVAILABLE',
  },
  capability: 'IMAGE_UNDERSTANDING',
  execution: 'SYNC',
});
if (!isCapabilityAvailable(descriptor.availability)) {
  throw new Error('能力未开通'); // 展示明确不可用；不做自动降级
}

// 2. 校验请求（尺寸/格式/张数/时长/语言/音色都在解析期拒绝）
const request = parseMultimodalRequest({
  capability: 'IMAGE_UNDERSTANDING',
  image: {
    fileId: 7,
    height: 800,
    mime: 'image/png',
    size: 51200,
    width: 1200,
  },
  instruction: '这张图里有哪些指标？',
  schemaVersion: '1.0',
});

// 3. 结果只引用私有文件编号，没有上游直链
const response = parseMultimodalResponse({
  capability: 'IMAGE_UNDERSTANDING',
  outcome: 'COMPLETED',
  result: { kind: 'image_understanding', text: '图中是月度销售趋势。' },
  schemaVersion: '1.0',
});
```

## 校验

在 `前端代码/basic-framework-admin` 下执行：

```bash
pnpm exec vitest run --dom packages/ai-contracts
pnpm exec eslint packages/ai-contracts
pnpm -F @vben/ai-contracts run typecheck
pnpm exec cspell lint "packages/ai-contracts/**/*.{ts,md}" --no-progress
```

## 与后端切片对拍（X01 合并清单）

本包的多模态契约以 X01 卡给出的维度（尺寸/格式/张数/时长/语言/音色）和后端冻结词汇为准，以下应用层取值仍需与后端准入矩阵/应用端接口逐项核对，不一致时两侧同时修改：

- 能力标识与执行形态（生成/编辑在应用端是持久任务还是同步返回）；
- 单文件字节上限（图片 10 MiB、音频 25 MiB）与音频时长 300 秒；
- 指令/提示词长度上限（4000 / 2000 / 2000）与 OCR 区域、STT 分段上限；
- 语言白名单（BCP-47 的 `zh-CN` 等，后端 `languageHint` 当前是可选的短码提示）；
- 音色标识形状与音色目录的下发方式（可用 TTS 必须登记 `voices`）；
- 私有文件引用字段命名（`fileId`/`name`/`mime`/`size`/`sha256` 对齐 A07 应用端 VO 与 `MediaFileRef`）；
- 不可用原因码与错误码映射（尤其 `SERVICE_RESOURCE_UNAVAILABLE` 是否在应用端使用）；
- 多页 OCR/文档页码属于文档管线（K04/X02），不在本契约的单图 OCR 结果内。
