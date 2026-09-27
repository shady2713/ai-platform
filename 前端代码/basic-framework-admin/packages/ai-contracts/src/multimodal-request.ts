import { z } from 'zod';

import {
  audioInputSchema,
  imageInputSchema,
  imageOutputFormatSchema,
  imageSizeSchema,
  mediaLanguageSchema,
  MULTIMODAL_LIMITS,
  MULTIMODAL_SCHEMA_VERSION,
  ttsOutputFormatSchema,
  voiceIdSchema,
} from './multimodal';

/**
 * 多模态能力请求契约（X01）：与 `multimodal.ts` 的词汇/结果/响应共用同一套约束。
 *
 * <p>请求里没有 `modelId`/`endpointId`/供应商/超时字段：模型与端点由平台按服务与准入矩阵解析，
 * 客户端指定上游属于未知字段，解析期直接拒绝（防"绕过准入换供应商"）。
 * 图片/音频输入只引用平台私有文件编号，不接受上游 URL。
 */

/** 图片理解请求：私有图片 + 理解指令（一次一张；多张由调用方编排多轮）。 */
export const imageUnderstandingRequestSchema = z
  .object({
    capability: z.literal('IMAGE_UNDERSTANDING'),
    image: imageInputSchema,
    instruction: z.string().min(1).max(MULTIMODAL_LIMITS.maxInstructionLength),
    schemaVersion: z.literal(MULTIMODAL_SCHEMA_VERSION),
  })
  .strict();

export type ImageUnderstandingRequest = z.infer<
  typeof imageUnderstandingRequestSchema
>;

/** OCR 请求：语言提示来自白名单；区域/置信度只在明确要求时返回。 */
export const imageOcrRequestSchema = z
  .object({
    capability: z.literal('IMAGE_OCR'),
    image: imageInputSchema,
    includeRegions: z.boolean().optional(),
    languageHint: mediaLanguageSchema.optional(),
    schemaVersion: z.literal(MULTIMODAL_SCHEMA_VERSION),
  })
  .strict();

export type ImageOcrRequest = z.infer<typeof imageOcrRequestSchema>;

/** 图片生成请求：提示词 + 受控尺寸/张数/格式；产物经转存后才是私有文件。 */
export const imageGenerationRequestSchema = z
  .object({
    capability: z.literal('IMAGE_GENERATION'),
    count: z
      .number()
      .int()
      .min(1)
      .max(MULTIMODAL_LIMITS.maxGenerationCount)
      .optional(),
    outputFormat: imageOutputFormatSchema.optional(),
    prompt: z.string().min(1).max(MULTIMODAL_LIMITS.maxGenerationPromptLength),
    schemaVersion: z.literal(MULTIMODAL_SCHEMA_VERSION),
    size: imageSizeSchema.optional(),
  })
  .strict();

export type ImageGenerationRequest = z.infer<
  typeof imageGenerationRequestSchema
>;

/** 图片编辑请求：私有源图 + 指令 + 可选尺寸/格式；源图失权必须在服务端拒绝。 */
export const imageEditRequestSchema = z
  .object({
    capability: z.literal('IMAGE_EDIT'),
    instruction: z
      .string()
      .min(1)
      .max(MULTIMODAL_LIMITS.maxEditInstructionLength),
    outputFormat: imageOutputFormatSchema.optional(),
    schemaVersion: z.literal(MULTIMODAL_SCHEMA_VERSION),
    size: imageSizeSchema.optional(),
    source: imageInputSchema,
  })
  .strict();

export type ImageEditRequest = z.infer<typeof imageEditRequestSchema>;

/** STT 请求：受控音频文件 + 可选语言提示（不实时，实时语音属 V1.2）。 */
export const speechToTextRequestSchema = z
  .object({
    audio: audioInputSchema,
    capability: z.literal('SPEECH_TO_TEXT'),
    languageHint: mediaLanguageSchema.optional(),
    schemaVersion: z.literal(MULTIMODAL_SCHEMA_VERSION),
  })
  .strict();

export type SpeechToTextRequest = z.infer<typeof speechToTextRequestSchema>;

/** TTS 请求：文本 + 白名单语言/音色/输出格式；产物是私有文件。 */
export const textToSpeechRequestSchema = z
  .object({
    capability: z.literal('TEXT_TO_SPEECH'),
    language: mediaLanguageSchema,
    outputFormat: ttsOutputFormatSchema.optional(),
    schemaVersion: z.literal(MULTIMODAL_SCHEMA_VERSION),
    text: z.string().min(1).max(MULTIMODAL_LIMITS.maxSpeechTextLength),
    voiceId: voiceIdSchema,
  })
  .strict();

export type TextToSpeechRequest = z.infer<typeof textToSpeechRequestSchema>;

export const multimodalRequestSchema = z.discriminatedUnion('capability', [
  imageEditRequestSchema,
  imageGenerationRequestSchema,
  imageOcrRequestSchema,
  imageUnderstandingRequestSchema,
  speechToTextRequestSchema,
  textToSpeechRequestSchema,
]);

export type MultimodalRequest = z.infer<typeof multimodalRequestSchema>;

/** 解析多模态请求；缺字段、超限、未知字段、未登记能力一律拒绝。 */
export function parseMultimodalRequest(input: unknown): MultimodalRequest {
  return multimodalRequestSchema.parse(input);
}
