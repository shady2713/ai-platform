import { z } from 'zod';

/**
 * 多模态能力契约（X01，FR-35/FR-36）：图片理解 / OCR / 图片生成 / 图片编辑 / 语音识别（STT）/
 * 语音合成（TTS）。
 *
 * <p>与后端 X01 冻结词汇对齐（合并时按这些锚点逐项核对）：
 * <ul>
 *   <li>能力标识与 `ModelCapability` 追加的六个媒体能力同名：`IMAGE_UNDERSTANDING`、
 *       `IMAGE_OCR`、`IMAGE_GENERATION`、`IMAGE_EDIT`、`SPEECH_TO_TEXT`、`TEXT_TO_SPEECH`；</li>
 *   <li>平台硬上限与 `MediaValues` / 各 Request record 一致：图片单边 1..8192、
 *       尺寸语法 `宽x高`、生成张数 1..8、生成格式 png/jpeg/webp、TTS 文本 ≤4096 码元、
 *       TTS 输出格式 mp3/wav/opus，端点声明的准入矩阵可以更窄；</li>
 *   <li>不可用与失败语义复用 `ModelException.Reason` 与 `AiErrorCodeConstants`（1_003_010_xxx）。</li>
 * </ul>
 *
 * <p>AT-068 的三条不变量（"能力未开通 → 明确不可用、无隐藏外发"）：
 * <ol>
 *   <li><b>能力标识显式</b>：请求带 `capability`、结果带 `kind`，两者必须一致；未登记能力在解析期拒绝，
 *       不允许把未知能力"尽力"映射到已有通道。</li>
 *   <li><b>结果只引用私有文件</b>：产物只以 {@link privateFileRefSchema}（中台文件编号）出现；
 *       契约里没有任何上游 URL、供应商、端点字段，因此不存在"直链外发"的数据形状；
 *       读取产物走受控文件接口并按当前授权再次判定。</li>
 *   <li><b>未开通即明确不可用</b>：{@link multimodalAvailabilitySchema} 用稳定 `reasonCode` +
 *       十位数字 `errorCode` 表达不可用；`outcome: 'UNAVAILABLE'` 是终态，消费方必须原样展示，
 *       不得自动改用其它供应商、不得把请求重发到未授权通道。</li>
 * </ol>
 *
 * <p>应用层策略值（单文件 10 MiB / 25 MiB、音频 300 秒、指令 2000/4000 字符、OCR 区域上限）
 * 是 X01 卡未逐项给出数值时的首版冻结候选：端点声明的准入矩阵可以更窄，
 * 与本契约不一致时两侧同时修改，不得只放宽一侧。
 */

export const MULTIMODAL_SCHEMA_VERSION = '1.0';

/**
 * 能力标识（与后端 `ModelCapability` 的追加顺序一致，取值稳定、不得改语义）。
 *
 * <p>`IMAGE_OCR` / `IMAGE_EDIT` 是冻结名称，不写成 `OCR` / `IMAGE_EDITING`。
 */
export const MULTIMODAL_CAPABILITIES = [
  'IMAGE_UNDERSTANDING',
  'IMAGE_OCR',
  'IMAGE_GENERATION',
  'IMAGE_EDIT',
  'SPEECH_TO_TEXT',
  'TEXT_TO_SPEECH',
] as const;

export const multimodalCapabilitySchema = z.enum(MULTIMODAL_CAPABILITIES);

export type MultimodalCapability = z.infer<typeof multimodalCapabilitySchema>;

/** 执行形态：生成/编辑由持久任务承接（异步受理），其余同步返回。 */
export const multimodalExecutionModeSchema = z.enum(['ASYNC', 'SYNC']);

export type MultimodalExecutionMode = z.infer<
  typeof multimodalExecutionModeSchema
>;

export const MULTIMODAL_CAPABILITY_EXECUTION = {
  IMAGE_UNDERSTANDING: 'SYNC',
  IMAGE_OCR: 'SYNC',
  IMAGE_GENERATION: 'ASYNC',
  IMAGE_EDIT: 'ASYNC',
  SPEECH_TO_TEXT: 'SYNC',
  TEXT_TO_SPEECH: 'SYNC',
} as const satisfies Record<MultimodalCapability, MultimodalExecutionMode>;

/** 受控上限：所有取值都是拒绝线，不得静默截断；端点声明的准入范围可以更窄。 */
export const MULTIMODAL_LIMITS = {
  /** 图片单边像素上限（与后端 `MediaValues.MAX_IMAGE_DIMENSION` 一致）。 */
  maxImageDimension: 8192,
  /** 应用层单张图片字节上限（10 MiB）。 */
  maxImageBytes: 10 * 1024 * 1024,
  /** 单次生成/编辑张数上限（与后端 `ImageGenerationRequest.MAX_COUNT` 一致）。 */
  maxGenerationCount: 8,
  /** 应用层音频字节上限（25 MiB）。 */
  maxAudioBytes: 25 * 1024 * 1024,
  /** 应用层非实时语音单段时长上限（300 秒）。 */
  maxAudioDurationMs: 300_000,
  /** 合成音频时长上限（20 分钟，覆盖 4096 码元文本）。 */
  maxSpeechOutputDurationMs: 1_200_000,
  /** 图片理解指令长度上限。 */
  maxInstructionLength: 4000,
  /** 图片生成提示词长度上限。 */
  maxGenerationPromptLength: 2000,
  /** 图片编辑指令长度上限。 */
  maxEditInstructionLength: 2000,
  /** TTS 文本长度上限（与后端 `SpeechSynthesisRequest.MAX_TEXT_LENGTH` 一致）。 */
  maxSpeechTextLength: 4096,
  /** OCR 单张图片识别区域数上限。 */
  maxOcrRegions: 1000,
  /** STT 分段字幕数上限。 */
  maxSpeechSegments: 2000,
} as const;

/**
 * 图片输入格式白名单：不接受 SVG（可执行/可外链）与动图；格式与魔数由服务端复核。
 */
export const IMAGE_MIME_TYPES = [
  'image/jpeg',
  'image/png',
  'image/webp',
] as const;

/** 音频输入格式白名单：不接受任意容器，格式探测由服务端复核。 */
export const AUDIO_MIME_TYPES = [
  'audio/mpeg',
  'audio/mp4',
  'audio/ogg',
  'audio/wav',
  'audio/webm',
] as const;

/** 生成/编辑输出格式（与后端 `ImageGenerationRequest.OUTPUT_FORMATS` 一致）。 */
export const IMAGE_OUTPUT_FORMATS = ['jpeg', 'png', 'webp'] as const;

/** TTS 输出格式（与后端 `SpeechSynthesisRequest.OUTPUT_FORMATS` 一致）。 */
export const TTS_OUTPUT_FORMATS = ['mp3', 'opus', 'wav'] as const;

export const imageMimeSchema = z.enum(IMAGE_MIME_TYPES);

export const audioMimeSchema = z.enum(AUDIO_MIME_TYPES);

export const imageOutputFormatSchema = z.enum(IMAGE_OUTPUT_FORMATS);

export const ttsOutputFormatSchema = z.enum(TTS_OUTPUT_FORMATS);

/** 私有文件/产物的媒体类型：只接受图片与音频白名单。 */
export const mediaMimeSchema = z.union([imageMimeSchema, audioMimeSchema]);

export type ImageMimeType = z.infer<typeof imageMimeSchema>;

export type AudioMimeType = z.infer<typeof audioMimeSchema>;

export type ImageOutputFormat = z.infer<typeof imageOutputFormatSchema>;

export type TtsOutputFormat = z.infer<typeof ttsOutputFormatSchema>;

export type MediaMimeType = z.infer<typeof mediaMimeSchema>;

/** 语言白名单（BCP-47 登记集合；端点可声明更窄范围，避免"自动猜语言"）。 */
export const MULTIMODAL_LANGUAGES = [
  'en-US',
  'ja-JP',
  'zh-CN',
  'zh-TW',
] as const;

export const mediaLanguageSchema = z.enum(MULTIMODAL_LANGUAGES);

export type MediaLanguage = z.infer<typeof mediaLanguageSchema>;

/**
 * 音色标识：必须是在能力目录（{@link multimodalCapabilityDescriptorSchema}.voices）登记过的
 * 标识形状；具体白名单由服务端按端点音色表下发，客户端不得硬编码或猜测供应商音色名。
 */
const VOICE_ID_PATTERN = /^[A-Za-z][\w-]{1,63}$/u;

export const voiceIdSchema = z
  .string()
  .min(2)
  .max(64)
  .regex(VOICE_ID_PATTERN, '音色必须是在能力目录登记的音色标识');

const sha256Schema = z
  .string()
  .regex(/^[0-9a-f]{64}$/u, '摘要必须是 64 位小写十六进制 SHA-256');

/**
 * 图片输入引用：`fileId` 由受控上传签发；宽高是客户端声明值（可省略），
 * 服务端必须复核魔数、真实尺寸与字节数后再外发。
 */
export const imageInputSchema = z
  .object({
    fileId: z.number().int().min(1),
    height: z
      .number()
      .int()
      .min(1)
      .max(MULTIMODAL_LIMITS.maxImageDimension)
      .optional(),
    mime: imageMimeSchema,
    name: z.string().min(1).max(200).optional(),
    sha256: sha256Schema.optional(),
    size: z.number().int().min(1).max(MULTIMODAL_LIMITS.maxImageBytes),
    width: z
      .number()
      .int()
      .min(1)
      .max(MULTIMODAL_LIMITS.maxImageDimension)
      .optional(),
  })
  .strict();

export type ImageInput = z.infer<typeof imageInputSchema>;

/** 音频输入引用：时长与字节都是拒绝线，超限在发请求前就拒绝。 */
export const audioInputSchema = z
  .object({
    durationMs: z
      .number()
      .int()
      .min(1)
      .max(MULTIMODAL_LIMITS.maxAudioDurationMs),
    fileId: z.number().int().min(1),
    mime: audioMimeSchema,
    name: z.string().min(1).max(200).optional(),
    sha256: sha256Schema.optional(),
    size: z.number().int().min(1).max(MULTIMODAL_LIMITS.maxAudioBytes),
  })
  .strict();

export type AudioInput = z.infer<typeof audioInputSchema>;

/**
 * 私有文件引用：中台文件编号 + 展示元数据（尺寸/时长缺失表示上游没有提供，不填假值）。
 * **没有 URL 字段**——上游临时地址与签名不得进入协议。
 */
export const privateFileRefSchema = z
  .object({
    durationMs: z
      .number()
      .int()
      .min(1)
      .max(MULTIMODAL_LIMITS.maxSpeechOutputDurationMs)
      .optional(),
    fileId: z.number().int().min(1),
    height: z
      .number()
      .int()
      .min(1)
      .max(MULTIMODAL_LIMITS.maxImageDimension)
      .optional(),
    mime: mediaMimeSchema,
    name: z.string().min(1).max(200),
    sha256: sha256Schema.optional(),
    size: z.number().int().min(1).max(MULTIMODAL_LIMITS.maxAudioBytes),
    width: z
      .number()
      .int()
      .min(1)
      .max(MULTIMODAL_LIMITS.maxImageDimension)
      .optional(),
  })
  .strict();

export type PrivateFileRef = z.infer<typeof privateFileRefSchema>;

/** 图片产物引用：媒体类型必须是图片白名单。 */
const privateImageFileRefSchema = privateFileRefSchema.refine(
  (file) => (IMAGE_MIME_TYPES as readonly string[]).includes(file.mime),
  { message: '图片产物必须是受支持的图片格式' },
);

/** 音频产物引用：媒体类型必须是音频白名单。 */
const privateAudioFileRefSchema = privateFileRefSchema.refine(
  (file) => (AUDIO_MIME_TYPES as readonly string[]).includes(file.mime),
  { message: '音频产物必须是受支持的音频格式' },
);

const IMAGE_SIZE_SYNTAX = /^(\d{1,5})x(\d{1,5})$/u;

/** 尺寸的可读拆分结果（`宽x高` 语法，供 UI 展示/预览使用）。 */
export interface ImageSizeParts {
  height: number;
  width: number;
}

/**
 * 解析受控尺寸字符串；语法非法或超出平台硬上限（单边 1..8192）返回 null。
 *
 * <p>与后端 `MediaValues.normalizeImageSize` 同语法：小写 `宽x高`，如 `1024x1024`。
 */
export function parseImageSize(size: string): ImageSizeParts | null {
  const match = IMAGE_SIZE_SYNTAX.exec(size);
  if (match === null) {
    return null;
  }
  const width = Number(match[1]);
  const height = Number(match[2]);
  if (
    width < 1 ||
    height < 1 ||
    width > MULTIMODAL_LIMITS.maxImageDimension ||
    height > MULTIMODAL_LIMITS.maxImageDimension
  ) {
    return null;
  }
  return { height, width };
}

/** 生成/编辑尺寸：`宽x高` 字符串，单边 1..8192；为空表示由端点默认值决定。 */
export const imageSizeSchema = z
  .string()
  .max(11)
  .regex(IMAGE_SIZE_SYNTAX, '尺寸必须形如 1024x1024')
  .refine((size) => parseImageSize(size) !== null, {
    message: '尺寸每个方向必须在 1-8192 之间',
  });

export type ImageSize = z.infer<typeof imageSizeSchema>;

export const usageSourceSchema = z.enum(['ESTIMATED', 'REPORTED', 'UNKNOWN']);

export type UsageSource = z.infer<typeof usageSourceSchema>;

export const usageUnitSchema = z.enum([
  'AUDIO_MILLISECOND',
  'CHARACTER',
  'IMAGE',
  'TOKEN',
]);

export type UsageUnit = z.infer<typeof usageUnitSchema>;

/** 金额一律十进制字符串（存储与传输保留原串），费用是按配置单价的估算值。 */
const decimalAmountSchema = z
  .string()
  .max(64)
  .regex(/^\d+(?:\.\d+)?$/u, '金额是十进制字符串，不使用浮点数');

/**
 * 用量与费用：上游未报告用量时必须 `source: 'UNKNOWN'` + `quantity: null`，
 * **不得填 0 冒充实测**（FR-31）；费用只在 `currency` 明确时出现。
 */
export const multimodalUsageSchema = z
  .object({
    currency: z.literal('CNY').optional(),
    estimatedCost: decimalAmountSchema.optional(),
    quantity: z.number().min(0).nullable(),
    source: usageSourceSchema,
    unit: usageUnitSchema,
  })
  .strict()
  .refine((usage) => usage.source !== 'UNKNOWN' || usage.quantity === null, {
    message: 'UNKNOWN 的数量必须是 null，不能用 0 冒充实测',
  })
  .refine((usage) => usage.source === 'UNKNOWN' || usage.quantity !== null, {
    message: 'REPORTED/ESTIMATED 必须给出数量',
  })
  .refine(
    (usage) =>
      usage.estimatedCost === undefined || usage.currency !== undefined,
    { message: '给出估算费用时必须带币种' },
  );

export type MultimodalUsage = z.infer<typeof multimodalUsageSchema>;

/** 图片理解结果：只有受控文本，没有图片直链；空文本属于协议异常，不接受。 */
export const imageUnderstandingResultSchema = z
  .object({
    kind: z.literal('image_understanding'),
    text: z.string().min(1).max(20_000),
    usage: multimodalUsageSchema.optional(),
  })
  .strict();

export type ImageUnderstandingResult = z.infer<
  typeof imageUnderstandingResultSchema
>;

/**
 * OCR 识别区域：区域/置信度都来自真实识别记录，坐标是相对图片的 0..1 归一化值；
 * 不确定的识别必须保留置信度，不得伪装人工核验。
 */
export const ocrRegionSchema = z
  .object({
    bounds: z
      .object({
        height: z.number().min(0).max(1),
        width: z.number().min(0).max(1),
        x: z.number().min(0).max(1),
        y: z.number().min(0).max(1),
      })
      .strict()
      .refine((box) => box.x + box.width <= 1 && box.y + box.height <= 1, {
        message: '识别区域必须在图片范围内',
      }),
    confidence: z.number().min(0).max(1),
    text: z.string().max(2000),
  })
  .strict();

export type OcrRegion = z.infer<typeof ocrRegionSchema>;

export const imageOcrResultSchema = z
  .object({
    kind: z.literal('image_ocr'),
    regions: z
      .array(ocrRegionSchema)
      .min(1)
      .max(MULTIMODAL_LIMITS.maxOcrRegions)
      .optional(),
    text: z.string().min(1).max(20_000),
    usage: multimodalUsageSchema.optional(),
  })
  .strict();

export type ImageOcrResult = z.infer<typeof imageOcrResultSchema>;

/** 生成/编辑产物：文件引用数组，无任何上游 URL 或供应商字段。 */
const imageArtifactResultShape = {
  files: z
    .array(privateImageFileRefSchema)
    .min(1)
    .max(MULTIMODAL_LIMITS.maxGenerationCount),
  usage: multimodalUsageSchema.optional(),
};

export const imageGenerationResultSchema = z
  .object({
    ...imageArtifactResultShape,
    kind: z.literal('image_generation'),
  })
  .strict();

export type ImageGenerationResult = z.infer<typeof imageGenerationResultSchema>;

export const imageEditResultSchema = z
  .object({
    ...imageArtifactResultShape,
    kind: z.literal('image_edit'),
  })
  .strict();

export type ImageEditResult = z.infer<typeof imageEditResultSchema>;

/** STT 分段字幕（与后端 `SpeechSegment` 同字段）：时间轴只描述识别结果，不承诺人工校对。 */
export const speechSegmentSchema = z
  .object({
    endMillis: z
      .number()
      .int()
      .min(0)
      .max(MULTIMODAL_LIMITS.maxAudioDurationMs),
    startMillis: z
      .number()
      .int()
      .min(0)
      .max(MULTIMODAL_LIMITS.maxAudioDurationMs),
    text: z.string().min(1).max(2000),
  })
  .strict()
  .refine((segment) => segment.endMillis >= segment.startMillis, {
    message: '分段结束时间不得早于开始时间',
  });

export type SpeechSegment = z.infer<typeof speechSegmentSchema>;

/** STT 结果：全文必须非空（空转写是上游协议异常，不接受）；分段可以为空。 */
export const speechToTextResultSchema = z
  .object({
    kind: z.literal('speech_to_text'),
    segments: z
      .array(speechSegmentSchema)
      .max(MULTIMODAL_LIMITS.maxSpeechSegments)
      .optional(),
    text: z.string().min(1).max(20_000),
    usage: multimodalUsageSchema.optional(),
  })
  .strict();

export type SpeechToTextResult = z.infer<typeof speechToTextResultSchema>;

/** TTS 结果：音频产物 + 实际音色；产物是私有文件，读取仍按当前授权判定。 */
export const textToSpeechResultSchema = z
  .object({
    file: privateAudioFileRefSchema,
    kind: z.literal('text_to_speech'),
    usage: multimodalUsageSchema.optional(),
    voiceId: voiceIdSchema,
  })
  .strict();

export type TextToSpeechResult = z.infer<typeof textToSpeechResultSchema>;

export const multimodalResultSchema = z.discriminatedUnion('kind', [
  imageEditResultSchema,
  imageGenerationResultSchema,
  imageOcrResultSchema,
  imageUnderstandingResultSchema,
  speechToTextResultSchema,
  textToSpeechResultSchema,
]);

export type MultimodalResult = z.infer<typeof multimodalResultSchema>;

export type MultimodalResultKind = MultimodalResult['kind'];

/** 能力 → 结果类型：响应里两者必须一致，防止"声明 A 能力返回 B 结果"。 */
export const MULTIMODAL_RESULT_KINDS = {
  IMAGE_UNDERSTANDING: 'image_understanding',
  IMAGE_OCR: 'image_ocr',
  IMAGE_GENERATION: 'image_generation',
  IMAGE_EDIT: 'image_edit',
  SPEECH_TO_TEXT: 'speech_to_text',
  TEXT_TO_SPEECH: 'text_to_speech',
} as const satisfies Record<MultimodalCapability, MultimodalResultKind>;

/**
 * 能力不可用的稳定原因码（与后端媒体准入闸门和既有错误码对应）：
 *
 * <ul>
 *   <li>`CAPABILITY_NOT_ENABLED`：端点未声明该能力，或声明了但未通过探测确认
 *       （后端 `ModelException.Reason.CAPABILITY_NOT_ENABLED`，任何网络请求之前拒绝）；</li>
 *   <li>`ENDPOINT_DISABLED`：端点停用；</li>
 *   <li>`OUTBOUND_BLOCKED`：资源等级不允许外发到该端点（策略先于网络调用）；</li>
 *   <li>`SERVICE_RESOURCE_UNAVAILABLE`：发布版本依赖的资源绑定不可用，新运行拒绝。</li>
 * </ul>
 */
export const MULTIMODAL_UNAVAILABLE_REASONS = [
  'CAPABILITY_NOT_ENABLED',
  'ENDPOINT_DISABLED',
  'OUTBOUND_BLOCKED',
  'SERVICE_RESOURCE_UNAVAILABLE',
] as const;

export const multimodalUnavailableReasonSchema = z.enum(
  MULTIMODAL_UNAVAILABLE_REASONS,
);

export type MultimodalUnavailableReason = z.infer<
  typeof multimodalUnavailableReasonSchema
>;

/**
 * 原因码 → 稳定错误码（十位数字，与开放 API 的数值业务码一致）。
 * 复用 `docs/contracts/ai/error-code-map.md` 与 X01 已加编号，不新增。
 */
export const MULTIMODAL_UNAVAILABLE_ERROR_CODES = {
  CAPABILITY_NOT_ENABLED: '1003002007',
  ENDPOINT_DISABLED: '1003002001',
  OUTBOUND_BLOCKED: '1003002006',
  SERVICE_RESOURCE_UNAVAILABLE: '1003008004',
} as const satisfies Record<MultimodalUnavailableReason, string>;

/** 返回原因码对应的稳定错误码（调用方按码分支，不解析文案）。 */
export function multimodalUnavailableErrorCode(
  reason: MultimodalUnavailableReason,
): string {
  return MULTIMODAL_UNAVAILABLE_ERROR_CODES[reason];
}

/**
 * 媒体请求/输出的既有失败码（X01，`AiErrorCodeConstants` 1_003_010_xxx）。
 *
 * <p>键名与 `docs/contracts/ai/error-code-map.md` 的 `AI_MEDIA_*` 常量一一对应（去掉 `AI_` 前缀），
 * 两侧共用一套名字，合并时按键名即可对拍；键名不改变错误码本身。
 */
export const MULTIMODAL_ERROR_CODES = {
  MEDIA_INPUT_DURATION_EXCEEDED: '1003010003',
  MEDIA_INPUT_TOO_LARGE: '1003010002',
  MEDIA_INPUT_TYPE_UNSUPPORTED: '1003010001',
  MEDIA_OUTPUT_EMPTY: '1003010004',
  MEDIA_REQUEST_INVALID: '1003010000',
} as const;

export type MultimodalErrorCode =
  (typeof MULTIMODAL_ERROR_CODES)[keyof typeof MULTIMODAL_ERROR_CODES];

const errorCodeSchema = z
  .string()
  .regex(/^\d{10}$/u, '错误码是十位数字的稳定码');

const unavailableShape = {
  errorCode: errorCodeSchema,
  message: z.string().min(1).max(200),
  reasonCode: multimodalUnavailableReasonSchema,
};

/** 不可用说明：必须能直接展示给用户，且不含任何"换供应商"语义。 */
export const multimodalUnavailableSchema = z.object(unavailableShape).strict();

export type MultimodalUnavailable = z.infer<typeof multimodalUnavailableSchema>;

/**
 * 能力可用性：只有 AVAILABLE 才允许发起调用；UNAVAILABLE 必须原样报告，
 * 不允许静默降级、不允许自动改用其它供应商。
 */
export const multimodalAvailabilitySchema = z.discriminatedUnion('state', [
  z.object({ state: z.literal('AVAILABLE') }).strict(),
  z
    .object({
      ...unavailableShape,
      state: z.literal('UNAVAILABLE'),
    })
    .strict(),
]);

export type MultimodalAvailability = z.infer<
  typeof multimodalAvailabilitySchema
>;

/** 可用性判定：调用方在发请求前必须用它门禁（不可用即明确拒绝）。 */
export function isCapabilityAvailable(
  availability: MultimodalAvailability,
): boolean {
  return availability.state === 'AVAILABLE';
}

/**
 * 能力目录条目：能力 + 执行形态 + 可用性 + 已登记白名单（语言/音色）。
 * TTS 在可用时必须带音色白名单，客户端由此渲染可选音色而不是硬编码供应商音色名。
 */
export const multimodalCapabilityDescriptorSchema = z
  .object({
    availability: multimodalAvailabilitySchema,
    capability: multimodalCapabilitySchema,
    execution: multimodalExecutionModeSchema,
    languages: z
      .array(mediaLanguageSchema)
      .min(1)
      .max(MULTIMODAL_LANGUAGES.length)
      .optional(),
    voices: z.array(voiceIdSchema).min(1).max(200).optional(),
  })
  .strict()
  .refine(
    (descriptor) =>
      descriptor.capability !== 'TEXT_TO_SPEECH' ||
      descriptor.voices !== undefined ||
      descriptor.availability.state === 'UNAVAILABLE',
    { message: '可用的 TTS 能力必须登记音色白名单' },
  )
  .refine(
    (descriptor) =>
      descriptor.capability === 'TEXT_TO_SPEECH' ||
      descriptor.voices === undefined,
    { message: '只有 TTS 能力登记音色白名单' },
  );

export type MultimodalCapabilityDescriptor = z.infer<
  typeof multimodalCapabilityDescriptorSchema
>;

/**
 * 音色白名单判定：`voiceId` 必须精确出现在能力目录登记的 `voices` 里（大小写敏感）。
 *
 * <p>与 {@link isCapabilityAvailable} 是两件事：这里判"音色是否登记"，那里判"能力此刻是否可用"；
 * 发 TTS 请求前两个门禁都要过。未登记一律返回 false——不得据此改猜供应商音色名，
 * 也不得把未登记音色发给端点"试试看"。
 */
export function isVoiceRegistered(
  descriptor: MultimodalCapabilityDescriptor,
  voiceId: string,
): boolean {
  return descriptor.voices?.includes(voiceId) ?? false;
}

/**
 * 响应：COMPLETED（同步能力结果）/ ACCEPTED（异步任务受理）/ UNAVAILABLE（能力未开通）。
 *
 * <p>UNAVAILABLE 是**终态**：调用方必须向用户显示不可用，不得回退到其它供应商、
 * 不得把请求重发到未被授权的外发通道。
 */
export const multimodalResponseSchema = z
  .discriminatedUnion('outcome', [
    z
      .object({
        capability: multimodalCapabilitySchema,
        outcome: z.literal('COMPLETED'),
        result: multimodalResultSchema,
        schemaVersion: z.literal(MULTIMODAL_SCHEMA_VERSION),
      })
      .strict(),
    z
      .object({
        capability: multimodalCapabilitySchema,
        outcome: z.literal('ACCEPTED'),
        runId: z.number().int().min(1),
        schemaVersion: z.literal(MULTIMODAL_SCHEMA_VERSION),
        status: z.enum(['QUEUED', 'RUNNING']),
      })
      .strict(),
    z
      .object({
        capability: multimodalCapabilitySchema,
        outcome: z.literal('UNAVAILABLE'),
        schemaVersion: z.literal(MULTIMODAL_SCHEMA_VERSION),
        unavailable: multimodalUnavailableSchema,
      })
      .strict(),
  ])
  .superRefine((response, ctx) => {
    if (response.outcome === 'UNAVAILABLE') {
      return;
    }
    if (response.outcome === 'ACCEPTED') {
      if (MULTIMODAL_CAPABILITY_EXECUTION[response.capability] !== 'ASYNC') {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: '只有异步能力可以返回 ACCEPTED',
        });
      }
      return;
    }
    if (response.result.kind !== MULTIMODAL_RESULT_KINDS[response.capability]) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: '结果类型必须与能力一致',
        path: ['result', 'kind'],
      });
    }
  });

export type MultimodalResponse = z.infer<typeof multimodalResponseSchema>;

/** 解析多模态响应；能力与结果类型不一致、或带替代供应商字段都必须拒绝。 */
export function parseMultimodalResponse(input: unknown): MultimodalResponse {
  return multimodalResponseSchema.parse(input);
}

/** 解析能力目录条目；TTS 缺音色白名单等不一致直接拒绝。 */
export function parseMultimodalCapabilityDescriptor(
  input: unknown,
): MultimodalCapabilityDescriptor {
  return multimodalCapabilityDescriptorSchema.parse(input);
}
