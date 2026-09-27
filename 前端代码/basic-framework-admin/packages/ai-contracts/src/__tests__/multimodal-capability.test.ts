import { describe, expect, it } from 'vitest';

import {
  imageEditResultSchema,
  imageGenerationResultSchema,
  imageOcrResultSchema,
  imageUnderstandingResultSchema,
  isCapabilityAvailable,
  MULTIMODAL_CAPABILITIES,
  MULTIMODAL_CAPABILITY_EXECUTION,
  MULTIMODAL_ERROR_CODES,
  MULTIMODAL_LIMITS,
  MULTIMODAL_RESULT_KINDS,
  MULTIMODAL_SCHEMA_VERSION,
  MULTIMODAL_UNAVAILABLE_ERROR_CODES,
  MULTIMODAL_UNAVAILABLE_REASONS,
  multimodalCapabilityDescriptorSchema,
  multimodalResponseSchema,
  multimodalResultSchema,
  multimodalUnavailableErrorCode,
  parseMultimodalCapabilityDescriptor,
  parseMultimodalResponse,
  privateFileRefSchema,
  speechToTextResultSchema,
  textToSpeechResultSchema,
} from '../index';

const SHA256 =
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

const IMAGE_FILE = {
  fileId: 21,
  height: 1024,
  mime: 'image/png',
  name: 'generated.png',
  sha256: SHA256,
  size: 90_112,
  width: 1024,
};

const AUDIO_FILE = {
  durationMs: 3200,
  fileId: 22,
  mime: 'audio/mpeg',
  name: 'reply.mp3',
  size: 40_960,
};

const UNAVAILABLE = {
  errorCode: '1003002007',
  message: '当前服务未开通该能力',
  reasonCode: 'CAPABILITY_NOT_ENABLED',
};

/** 结果与能力目录里绝不能出现的字段名：直链、换供应商、上游端点。 */
const OUTBOUND_KEY = /(?:url|uri|link|provider|endpoint|fallback)/iu;

const FORBIDDEN_FIELDS = [
  'alternateProvider',
  'directUrl',
  'downloadUrl',
  'endpointUrl',
  'fallbackProvider',
  'providerOverride',
  'upstreamUrl',
];

function expectRejected(parse: (input: unknown) => unknown, input: unknown) {
  expect(() => parse(input)).toThrow();
}

describe('多模态结果契约（X01）', () => {
  it('六类结果都能解析，kind 与能力 1:1', () => {
    const results = [
      imageUnderstandingResultSchema.parse({
        kind: 'image_understanding',
        text: '图中是月度销售趋势。',
      }),
      imageOcrResultSchema.parse({
        kind: 'image_ocr',
        regions: [
          {
            bounds: { height: 0.1, width: 0.2, x: 0.5, y: 0.4 },
            confidence: 0.87,
            text: '合计 100',
          },
        ],
        text: '合计 100',
      }),
      imageGenerationResultSchema.parse({
        files: [IMAGE_FILE],
        kind: 'image_generation',
      }),
      imageEditResultSchema.parse({ files: [IMAGE_FILE], kind: 'image_edit' }),
      speechToTextResultSchema.parse({
        kind: 'speech_to_text',
        segments: [
          { endMillis: 3000, startMillis: 0, text: '你好' },
          { endMillis: 6000, startMillis: 3000, text: '世界' },
        ],
        text: '你好世界',
      }),
      textToSpeechResultSchema.parse({
        file: AUDIO_FILE,
        kind: 'text_to_speech',
        voiceId: 'zh-CN-female-01',
      }),
    ];

    expect(results.map((result) => result.kind)).toStrictEqual([
      'image_understanding',
      'image_ocr',
      'image_generation',
      'image_edit',
      'speech_to_text',
      'text_to_speech',
    ]);
    expect(multimodalResultSchema.options).toHaveLength(
      MULTIMODAL_CAPABILITIES.length,
    );
    expect(MULTIMODAL_RESULT_KINDS.IMAGE_OCR).toBe('image_ocr');
    expect(MULTIMODAL_RESULT_KINDS.IMAGE_EDIT).toBe('image_edit');
  });

  it('空产物/空文本属于协议异常，不接受', () => {
    expectRejected(imageGenerationResultSchema.parse, {
      files: [],
      kind: 'image_generation',
    });
    expectRejected(imageEditResultSchema.parse, {
      files: [],
      kind: 'image_edit',
    });
    expectRejected(imageUnderstandingResultSchema.parse, {
      kind: 'image_understanding',
      text: '',
    });
    expectRejected(imageOcrResultSchema.parse, { kind: 'image_ocr', text: '' });
    expectRejected(speechToTextResultSchema.parse, {
      kind: 'speech_to_text',
      text: '',
    });
    expectRejected(imageGenerationResultSchema.parse, {
      files: [IMAGE_FILE],
      kind: 'image_generation',
      usage: undefined,
      upstreamUrl: 'https://upstream.example.com/object.png',
    });
  });

  it('图片/音频产物必须与产物通道媒体类型一致', () => {
    expectRejected(imageGenerationResultSchema.parse, {
      files: [{ ...IMAGE_FILE, mime: 'audio/mpeg' }],
      kind: 'image_generation',
    });
    expectRejected(textToSpeechResultSchema.parse, {
      file: { ...AUDIO_FILE, mime: 'image/png' },
      kind: 'text_to_speech',
      voiceId: 'zh-CN-female-01',
    });
    expect(
      textToSpeechResultSchema.parse({
        file: AUDIO_FILE,
        kind: 'text_to_speech',
        voiceId: 'zh-CN-female-01',
      }).kind,
    ).toBe('text_to_speech');
  });

  it('结果只引用私有文件编号：schema 里没有 URL/供应商字段', () => {
    const generated = imageGenerationResultSchema.parse({
      files: [IMAGE_FILE],
      kind: 'image_generation',
      usage: { quantity: 1, source: 'REPORTED', unit: 'IMAGE' },
    });
    expect(Object.keys(generated.files[0] ?? {}).toSorted()).toStrictEqual([
      'fileId',
      'height',
      'mime',
      'name',
      'sha256',
      'size',
      'width',
    ]);

    for (const key of Object.keys(privateFileRefSchema.shape)) {
      expect(key).not.toMatch(OUTBOUND_KEY);
    }
    for (const schema of [
      imageUnderstandingResultSchema,
      imageOcrResultSchema,
      imageGenerationResultSchema,
      imageEditResultSchema,
      speechToTextResultSchema,
      textToSpeechResultSchema,
    ]) {
      for (const key of Object.keys(schema.shape)) {
        expect(key).not.toMatch(OUTBOUND_KEY);
      }
    }

    for (const field of FORBIDDEN_FIELDS) {
      expectRejected(privateFileRefSchema.parse, {
        ...IMAGE_FILE,
        [field]: 'https://upstream.example.com/object.png',
      });
      expectRejected(parseMultimodalResponse, {
        capability: 'IMAGE_GENERATION',
        outcome: 'COMPLETED',
        result: {
          files: [{ ...IMAGE_FILE, [field]: 'https://upstream.example.com' }],
          kind: 'image_generation',
        },
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      });
    }
  });

  it('ocr 区域带区域与置信度，越界与超范围被拒', () => {
    const parsed = imageOcrResultSchema.parse({
      kind: 'image_ocr',
      regions: [
        {
          bounds: { height: 0.1, width: 0.2, x: 0.5, y: 0.4 },
          confidence: 0.87,
          text: '合计 100',
        },
      ],
      text: '合计 100',
    });
    expect(parsed.regions?.[0]?.confidence).toBe(0.87);
    expectRejected(imageOcrResultSchema.parse, {
      kind: 'image_ocr',
      regions: [
        {
          bounds: { height: 0.4, width: 0.8, x: 0.5, y: 0.4 },
          confidence: 0.87,
          text: '越界',
        },
      ],
      text: '越界',
    });
    expectRejected(imageOcrResultSchema.parse, {
      kind: 'image_ocr',
      regions: [
        {
          bounds: { height: 0.1, width: 0.2, x: 0.1, y: 0.1 },
          confidence: 1.5,
          text: '置信度越界',
        },
      ],
      text: '置信度越界',
    });
    expectRejected(imageOcrResultSchema.parse, {
      kind: 'image_ocr',
      regions: [],
      text: '空区域列表要么不传，要么至少一个',
    });
  });

  it('stt 分段与 tts 时长约束生效', () => {
    expectRejected(speechToTextResultSchema.parse, {
      kind: 'speech_to_text',
      segments: [{ endMillis: 1000, startMillis: 2000, text: '倒序' }],
      text: '倒序',
    });
    expectRejected(speechToTextResultSchema.parse, {
      kind: 'speech_to_text',
      segments: [{ endMillis: 1000, startMillis: 0, text: '' }],
      text: '空分段',
    });
    expectRejected(speechToTextResultSchema.parse, {
      kind: 'speech_to_text',
      segments: Array.from(
        { length: MULTIMODAL_LIMITS.maxSpeechSegments + 1 },
        () => ({ endMillis: 1, startMillis: 0, text: 'x' }),
      ),
      text: '超量分段',
    });
    expectRejected(textToSpeechResultSchema.parse, {
      file: { ...AUDIO_FILE, durationMs: 0 },
      kind: 'text_to_speech',
      voiceId: 'zh-CN-female-01',
    });
  });

  it('用量与费用：UNKNOWN 用 null 而不是 0，估算费用必须带币种', () => {
    expect(
      imageGenerationResultSchema.parse({
        files: [IMAGE_FILE],
        kind: 'image_generation',
        usage: { quantity: null, source: 'UNKNOWN', unit: 'IMAGE' },
      }).usage?.quantity,
    ).toBeNull();
    expect(
      imageGenerationResultSchema.parse({
        files: [IMAGE_FILE],
        kind: 'image_generation',
        usage: {
          currency: 'CNY',
          estimatedCost: '0.35',
          quantity: null,
          source: 'UNKNOWN',
          unit: 'IMAGE',
        },
      }).usage?.estimatedCost,
    ).toBe('0.35');
    expectRejected(imageGenerationResultSchema.parse, {
      files: [IMAGE_FILE],
      kind: 'image_generation',
      usage: { quantity: 0, source: 'UNKNOWN', unit: 'IMAGE' },
    });
    expectRejected(imageGenerationResultSchema.parse, {
      files: [IMAGE_FILE],
      kind: 'image_generation',
      usage: { quantity: null, source: 'REPORTED', unit: 'IMAGE' },
    });
    expectRejected(imageGenerationResultSchema.parse, {
      files: [IMAGE_FILE],
      kind: 'image_generation',
      usage: {
        estimatedCost: '0.35',
        quantity: 1,
        source: 'ESTIMATED',
        unit: 'IMAGE',
      },
    });
    expectRejected(imageGenerationResultSchema.parse, {
      files: [IMAGE_FILE],
      kind: 'image_generation',
      usage: {
        currency: 'CNY',
        estimatedCost: '1e3',
        quantity: 1,
        source: 'REPORTED',
        unit: 'IMAGE',
      },
    });
    expectRejected(imageGenerationResultSchema.parse, {
      files: [IMAGE_FILE],
      kind: 'image_generation',
      usage: { quantity: 1, source: 'REPORTED', unit: 'USD' },
    });
  });
});

describe('多模态响应与能力目录（X01）', () => {
  it('同步结果、异步受理与不可用终态都能解析', () => {
    expect(
      parseMultimodalResponse({
        capability: 'IMAGE_UNDERSTANDING',
        outcome: 'COMPLETED',
        result: { kind: 'image_understanding', text: '图中是月度销售趋势。' },
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      }).outcome,
    ).toBe('COMPLETED');
    expect(
      parseMultimodalResponse({
        capability: 'IMAGE_GENERATION',
        outcome: 'ACCEPTED',
        runId: 42,
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        status: 'QUEUED',
      }).outcome,
    ).toBe('ACCEPTED');
    expect(
      parseMultimodalResponse({
        capability: 'IMAGE_OCR',
        outcome: 'UNAVAILABLE',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        unavailable: UNAVAILABLE,
      }).outcome,
    ).toBe('UNAVAILABLE');
    expect(multimodalResponseSchema).toBeDefined();
  });

  it('执行形态在响应里被强制：只有异步能力能返回受理态', () => {
    expect(
      parseMultimodalResponse({
        capability: 'IMAGE_EDIT',
        outcome: 'ACCEPTED',
        runId: 43,
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        status: 'RUNNING',
      }).outcome,
    ).toBe('ACCEPTED');
    expectRejected(parseMultimodalResponse, {
      capability: 'IMAGE_OCR',
      outcome: 'ACCEPTED',
      runId: 44,
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      status: 'QUEUED',
    });
    expectRejected(parseMultimodalResponse, {
      capability: 'SPEECH_TO_TEXT',
      outcome: 'ACCEPTED',
      runId: 45,
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      status: 'QUEUED',
    });
    expect(MULTIMODAL_CAPABILITY_EXECUTION.IMAGE_GENERATION).toBe('ASYNC');
    expect(MULTIMODAL_CAPABILITY_EXECUTION.TEXT_TO_SPEECH).toBe('SYNC');
  });

  it('结果类型必须与能力一致，未知响应字段与版本被拒绝', () => {
    expectRejected(parseMultimodalResponse, {
      capability: 'IMAGE_OCR',
      outcome: 'COMPLETED',
      result: {
        files: [IMAGE_FILE],
        kind: 'image_generation',
      },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalResponse, {
      capability: 'IMAGE_OCR',
      outcome: 'COMPLETED',
      result: { kind: 'image_ocr', text: '合计 100' },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      upstreamUrl: 'https://upstream.example.com/result.json',
    });
    expectRejected(parseMultimodalResponse, {
      capability: 'IMAGE_OCR',
      outcome: 'COMPLETED',
      result: { kind: 'image_ocr', text: '合计 100' },
      schemaVersion: '2.0',
    });
    expectRejected(parseMultimodalResponse, {
      capability: 'IMAGE_OCR',
      outcome: 'FAILED',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
  });

  it('能力未开通是稳定原因码与十位错误码，且判定为不可用', () => {
    expect(MULTIMODAL_UNAVAILABLE_REASONS).toStrictEqual([
      'CAPABILITY_NOT_ENABLED',
      'ENDPOINT_DISABLED',
      'OUTBOUND_BLOCKED',
      'SERVICE_RESOURCE_UNAVAILABLE',
    ]);
    for (const reason of MULTIMODAL_UNAVAILABLE_REASONS) {
      const code = multimodalUnavailableErrorCode(reason);
      expect(code).toMatch(/^\d{10}$/u);
      expect(code).toBe(MULTIMODAL_UNAVAILABLE_ERROR_CODES[reason]);
    }
    expect(multimodalUnavailableErrorCode('CAPABILITY_NOT_ENABLED')).toBe(
      '1003002007',
    );
    expect(MULTIMODAL_ERROR_CODES).toStrictEqual({
      MEDIA_INPUT_DURATION_EXCEEDED: '1003010003',
      MEDIA_INPUT_TOO_LARGE: '1003010002',
      MEDIA_INPUT_TYPE_UNSUPPORTED: '1003010001',
      MEDIA_OUTPUT_EMPTY: '1003010004',
      MEDIA_REQUEST_INVALID: '1003010000',
    });
    expect(
      isCapabilityAvailable({
        errorCode: '1003002007',
        message: '当前服务未开通 OCR',
        reasonCode: 'CAPABILITY_NOT_ENABLED',
        state: 'UNAVAILABLE',
      }),
    ).toBe(false);
    expect(isCapabilityAvailable({ state: 'AVAILABLE' })).toBe(true);
  });

  it('不可用响应是终态：换供应商字段、符号错误码与未登记原因都被拒绝', () => {
    const parsed = parseMultimodalResponse({
      capability: 'SPEECH_TO_TEXT',
      outcome: 'UNAVAILABLE',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      unavailable: {
        errorCode: multimodalUnavailableErrorCode('CAPABILITY_NOT_ENABLED'),
        message: '当前服务未开通语音识别能力',
        reasonCode: 'CAPABILITY_NOT_ENABLED',
      },
    });
    expect(parsed.outcome).toBe('UNAVAILABLE');

    for (const field of FORBIDDEN_FIELDS) {
      expectRejected(parseMultimodalResponse, {
        capability: 'SPEECH_TO_TEXT',
        outcome: 'UNAVAILABLE',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        unavailable: { ...UNAVAILABLE, [field]: 'vendor-b' },
      });
    }
    expectRejected(parseMultimodalResponse, {
      capability: 'SPEECH_TO_TEXT',
      outcome: 'UNAVAILABLE',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      unavailable: {
        errorCode: 'AI_MODEL_CAPABILITY_NOT_ENABLED',
        message: '符号名不是线上的十位稳定码',
        reasonCode: 'CAPABILITY_NOT_ENABLED',
      },
    });
    expectRejected(parseMultimodalResponse, {
      capability: 'SPEECH_TO_TEXT',
      outcome: 'UNAVAILABLE',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      unavailable: { ...UNAVAILABLE, reasonCode: 'FALLBACK_TO_VENDOR_B' },
    });
  });

  it('能力目录：可用 TTS 必须登记音色白名单，非 TTS 不得登记音色', () => {
    expect(
      parseMultimodalCapabilityDescriptor({
        availability: { state: 'AVAILABLE' },
        capability: 'TEXT_TO_SPEECH',
        execution: 'SYNC',
        languages: ['zh-CN'],
        voices: ['zh-CN-female-01', 'zh-CN-male-01'],
      }).voices,
    ).toHaveLength(2);
    expectRejected(parseMultimodalCapabilityDescriptor, {
      availability: { state: 'AVAILABLE' },
      capability: 'TEXT_TO_SPEECH',
      execution: 'SYNC',
    });
    expectRejected(parseMultimodalCapabilityDescriptor, {
      availability: { state: 'AVAILABLE' },
      capability: 'IMAGE_OCR',
      execution: 'SYNC',
      voices: ['zh-CN-female-01'],
    });
    expectRejected(parseMultimodalCapabilityDescriptor, 'IMAGE_OCR');
    expectRejected(multimodalCapabilityDescriptorSchema.parse, {
      availability: { state: 'AVAILABLE' },
      capability: 'IMAGE_OCR',
      execution: 'SYNC',
      provider: 'vendor-a',
    });
    expect(
      parseMultimodalCapabilityDescriptor({
        availability: {
          errorCode: '1003002001',
          message: '端点已停用',
          reasonCode: 'ENDPOINT_DISABLED',
          state: 'UNAVAILABLE',
        },
        capability: 'TEXT_TO_SPEECH',
        execution: 'SYNC',
      }).availability.state,
    ).toBe('UNAVAILABLE');
    expectRejected(multimodalCapabilityDescriptorSchema.parse, {
      availability: { state: 'PROBING' },
      capability: 'IMAGE_OCR',
      execution: 'SYNC',
    });
  });

  it('能力词汇、结果类型、错误码与平台上限冻结', () => {
    expect(MULTIMODAL_CAPABILITIES).toStrictEqual([
      'IMAGE_UNDERSTANDING',
      'IMAGE_OCR',
      'IMAGE_GENERATION',
      'IMAGE_EDIT',
      'SPEECH_TO_TEXT',
      'TEXT_TO_SPEECH',
    ]);
    expect(MULTIMODAL_CAPABILITY_EXECUTION).toStrictEqual({
      IMAGE_UNDERSTANDING: 'SYNC',
      IMAGE_OCR: 'SYNC',
      IMAGE_GENERATION: 'ASYNC',
      IMAGE_EDIT: 'ASYNC',
      SPEECH_TO_TEXT: 'SYNC',
      TEXT_TO_SPEECH: 'SYNC',
    });
    expect(MULTIMODAL_RESULT_KINDS).toStrictEqual({
      IMAGE_UNDERSTANDING: 'image_understanding',
      IMAGE_OCR: 'image_ocr',
      IMAGE_GENERATION: 'image_generation',
      IMAGE_EDIT: 'image_edit',
      SPEECH_TO_TEXT: 'speech_to_text',
      TEXT_TO_SPEECH: 'text_to_speech',
    });
    expect(MULTIMODAL_SCHEMA_VERSION).toBe('1.0');
    expect(MULTIMODAL_LIMITS.maxImageDimension).toBe(8192);
    expect(MULTIMODAL_LIMITS.maxGenerationCount).toBe(8);
    expect(MULTIMODAL_LIMITS.maxSpeechTextLength).toBe(4096);
  });
});
