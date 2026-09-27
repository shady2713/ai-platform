import { describe, expect, it } from 'vitest';

import {
  AUDIO_MIME_TYPES,
  IMAGE_MIME_TYPES,
  IMAGE_OUTPUT_FORMATS,
  imageEditRequestSchema,
  imageGenerationRequestSchema,
  imageOcrRequestSchema,
  imageSizeSchema,
  imageUnderstandingRequestSchema,
  MULTIMODAL_CAPABILITIES,
  MULTIMODAL_LANGUAGES,
  MULTIMODAL_LIMITS,
  MULTIMODAL_SCHEMA_VERSION,
  multimodalRequestSchema,
  parseImageSize,
  parseMultimodalRequest,
  speechToTextRequestSchema,
  textToSpeechRequestSchema,
  TTS_OUTPUT_FORMATS,
} from '../index';

const IMAGE = {
  fileId: 7,
  height: 800,
  mime: 'image/png',
  name: 'chart.png',
  size: 51_200,
  width: 1200,
};

const AUDIO = {
  durationMs: 12_000,
  fileId: 9,
  mime: 'audio/wav',
  name: 'voice.wav',
  size: 256_000,
};

const SHA256 =
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

/** 渲染/请求层绝不能出现的字段名：直链、换供应商、前端选模型/端点。 */
const OUTBOUND_KEY = /(?:url|uri|link|provider|endpoint|fallback|modelid)/iu;

const FORBIDDEN_FIELDS = [
  'alternateProvider',
  'directUrl',
  'downloadUrl',
  'endpointUrl',
  'fallbackProvider',
  'modelId',
  'providerOverride',
  'upstreamUrl',
];

function expectRejected(parse: (input: unknown) => unknown, input: unknown) {
  expect(() => parse(input)).toThrow();
}

describe('多模态请求契约（X01）', () => {
  it('六类能力的合法请求都按 capability 判别通过', () => {
    const requests = [
      imageUnderstandingRequestSchema.parse({
        capability: 'IMAGE_UNDERSTANDING',
        image: IMAGE,
        instruction: '这张图里有哪些指标？',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      }),
      imageOcrRequestSchema.parse({
        capability: 'IMAGE_OCR',
        image: { ...IMAGE, fileId: 8, sha256: SHA256 },
        includeRegions: true,
        languageHint: 'zh-CN',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      }),
      imageGenerationRequestSchema.parse({
        capability: 'IMAGE_GENERATION',
        count: 2,
        outputFormat: 'webp',
        prompt: '一张销售看板配图',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        size: '1024x1024',
      }),
      imageEditRequestSchema.parse({
        capability: 'IMAGE_EDIT',
        instruction: '把背景改成浅灰',
        outputFormat: 'png',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        size: '1536x1024',
        source: IMAGE,
      }),
      speechToTextRequestSchema.parse({
        audio: AUDIO,
        capability: 'SPEECH_TO_TEXT',
        languageHint: 'zh-CN',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      }),
      textToSpeechRequestSchema.parse({
        capability: 'TEXT_TO_SPEECH',
        language: 'zh-CN',
        outputFormat: 'opus',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        text: '本月销售额已完成目标。',
        voiceId: 'zh-CN-female-01',
      }),
    ];

    expect(requests.map((request) => request.capability)).toStrictEqual([
      'IMAGE_UNDERSTANDING',
      'IMAGE_OCR',
      'IMAGE_GENERATION',
      'IMAGE_EDIT',
      'SPEECH_TO_TEXT',
      'TEXT_TO_SPEECH',
    ]);
    expect(
      parseMultimodalRequest({
        capability: 'IMAGE_OCR',
        image: IMAGE,
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      }).capability,
    ).toBe('IMAGE_OCR');
    expect(multimodalRequestSchema.options).toHaveLength(
      MULTIMODAL_CAPABILITIES.length,
    );
  });

  it('缺字段、缺版本号、未登记能力与未知版本一律拒绝', () => {
    expectRejected(parseMultimodalRequest, null);
    expectRejected(parseMultimodalRequest, 'IMAGE_OCR');
    expectRejected(parseMultimodalRequest, []);
    expectRejected(parseMultimodalRequest, {
      capability: 'VIDEO_UNDERSTANDING',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    // 旧草稿名（OCR / IMAGE_EDITING）不是冻结词汇
    expectRejected(parseMultimodalRequest, {
      capability: 'OCR',
      image: IMAGE,
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_EDITING',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      source: IMAGE,
    });
    // 缺 schemaVersion / 缺指令 / 缺图片 / 缺 capability
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_UNDERSTANDING',
      image: IMAGE,
      instruction: '描述',
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_UNDERSTANDING',
      image: IMAGE,
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_OCR',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalRequest, { image: IMAGE });
    // 图片引用缺 fileId / 音频引用缺 durationMs
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_OCR',
      image: { ...IMAGE, fileId: undefined },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalRequest, {
      audio: { ...AUDIO, durationMs: undefined },
      capability: 'SPEECH_TO_TEXT',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    // TTS 缺音色与文本，STT 缺音频
    expectRejected(parseMultimodalRequest, {
      capability: 'TEXT_TO_SPEECH',
      language: 'zh-CN',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      text: '合成这句话',
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'SPEECH_TO_TEXT',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    // 未知 schemaVersion
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_OCR',
      image: IMAGE,
      schemaVersion: '2.0',
    });
  });

  it('张数、尺寸、时长与文本长度超限都被拒绝', () => {
    // 生成张数 8 是平台硬上限（后端 MAX_COUNT），9 拒绝
    expect(
      parseMultimodalRequest({
        capability: 'IMAGE_GENERATION',
        count: MULTIMODAL_LIMITS.maxGenerationCount,
        prompt: '边界张数',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      }).capability,
    ).toBe('IMAGE_GENERATION');
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_GENERATION',
      count: MULTIMODAL_LIMITS.maxGenerationCount + 1,
      prompt: '超量生成',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    // 8192 是单边上限，8193 拒绝；TTS 文本 4096 是上限，4097 拒绝
    expect(
      parseMultimodalRequest({
        capability: 'IMAGE_GENERATION',
        prompt: '边界尺寸',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        size: '8192x8192',
      }).capability,
    ).toBe('IMAGE_GENERATION');
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_GENERATION',
      prompt: '尺寸越界',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      size: '8193x1024',
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_GENERATION',
      prompt: '尺寸为零',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      size: '0x1024',
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_GENERATION',
      prompt: '尺寸格式错误',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      size: '1024X1024',
    });
    expect(
      parseMultimodalRequest({
        capability: 'TEXT_TO_SPEECH',
        language: 'zh-CN',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        text: 'x'.repeat(MULTIMODAL_LIMITS.maxSpeechTextLength),
        voiceId: 'zh-CN-female-01',
      }).capability,
    ).toBe('TEXT_TO_SPEECH');
    expectRejected(parseMultimodalRequest, {
      capability: 'TEXT_TO_SPEECH',
      language: 'zh-CN',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      text: 'x'.repeat(MULTIMODAL_LIMITS.maxSpeechTextLength + 1),
      voiceId: 'zh-CN-female-01',
    });
    // 指令与提示词长度
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_UNDERSTANDING',
      image: IMAGE,
      instruction: 'x'.repeat(MULTIMODAL_LIMITS.maxInstructionLength + 1),
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_EDIT',
      instruction: 'x'.repeat(MULTIMODAL_LIMITS.maxEditInstructionLength + 1),
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      source: IMAGE,
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_GENERATION',
      prompt: 'x'.repeat(MULTIMODAL_LIMITS.maxGenerationPromptLength + 1),
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    // 图片字节与音频字节/时长
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_OCR',
      image: { ...IMAGE, size: MULTIMODAL_LIMITS.maxImageBytes + 1 },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalRequest, {
      audio: { ...AUDIO, size: MULTIMODAL_LIMITS.maxAudioBytes + 1 },
      capability: 'SPEECH_TO_TEXT',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalRequest, {
      audio: {
        ...AUDIO,
        durationMs: MULTIMODAL_LIMITS.maxAudioDurationMs + 1,
      },
      capability: 'SPEECH_TO_TEXT',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    // 客户端声明的宽高也不能越界
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_OCR',
      image: { ...IMAGE, height: MULTIMODAL_LIMITS.maxImageDimension + 1 },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    // 未登记语言
    expectRejected(parseMultimodalRequest, {
      capability: 'TEXT_TO_SPEECH',
      language: 'fr-FR',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      text: 'bonjour',
      voiceId: 'fr-female-01',
    });
  });

  it('格式白名单：输入不接受 SVG/GIF/未知容器，输出格式与音色形状受控', () => {
    for (const mime of ['image/svg+xml', 'image/gif', 'application/pdf']) {
      expectRejected(parseMultimodalRequest, {
        capability: 'IMAGE_OCR',
        image: { ...IMAGE, mime },
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      });
    }
    for (const mime of ['audio/amr', 'audio/aac', 'video/mp4']) {
      expectRejected(parseMultimodalRequest, {
        audio: { ...AUDIO, mime },
        capability: 'SPEECH_TO_TEXT',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      });
    }
    expect(IMAGE_MIME_TYPES).toContain('image/webp');
    expect(AUDIO_MIME_TYPES).toContain('audio/webm');
    expect(IMAGE_OUTPUT_FORMATS).toStrictEqual(['jpeg', 'png', 'webp']);
    expect(TTS_OUTPUT_FORMATS).toStrictEqual(['mp3', 'opus', 'wav']);
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_GENERATION',
      outputFormat: 'gif',
      prompt: '不支持的格式',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'TEXT_TO_SPEECH',
      language: 'zh-CN',
      outputFormat: 'ogg',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      text: '格式不支持',
      voiceId: 'zh-CN-female-01',
    });
    expectRejected(parseMultimodalRequest, {
      capability: 'TEXT_TO_SPEECH',
      language: 'zh-CN',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      text: '音色非法',
      voiceId: 'https://vendor.example.com/voice?id=1',
    });
    expect(MULTIMODAL_LANGUAGES).toStrictEqual([
      'en-US',
      'ja-JP',
      'zh-CN',
      'zh-TW',
    ]);
  });

  it('sha256 只接受 64 位小写十六进制', () => {
    expect(
      parseMultimodalRequest({
        capability: 'IMAGE_UNDERSTANDING',
        image: { ...IMAGE, sha256: SHA256 },
        instruction: '校验摘要',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      }).capability,
    ).toBe('IMAGE_UNDERSTANDING');
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_UNDERSTANDING',
      image: { ...IMAGE, sha256: SHA256.toUpperCase() },
      instruction: '大写摘要不合法',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
    expectRejected(parseMultimodalRequest, {
      audio: { ...AUDIO, sha256: 'abc' },
      capability: 'SPEECH_TO_TEXT',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    });
  });

  it('未知字段与"换供应商/换端点/直链"字段都被拒绝', () => {
    for (const field of FORBIDDEN_FIELDS) {
      expectRejected(parseMultimodalRequest, {
        capability: 'IMAGE_GENERATION',
        prompt: '画一张图',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
        [field]: field === 'modelId' ? 42 : 'https://upstream.example.com/v1',
      });
      expectRejected(parseMultimodalRequest, {
        audio: { ...AUDIO, [field]: 'https://upstream.example.com/audio' },
        capability: 'SPEECH_TO_TEXT',
        schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      });
    }
    expectRejected(parseMultimodalRequest, {
      capability: 'IMAGE_OCR',
      image: IMAGE,
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      unknown: true,
    });
    for (const schema of [
      imageUnderstandingRequestSchema,
      imageOcrRequestSchema,
      imageGenerationRequestSchema,
      imageEditRequestSchema,
      speechToTextRequestSchema,
      textToSpeechRequestSchema,
    ]) {
      for (const key of Object.keys(schema.shape)) {
        expect(key).not.toMatch(OUTBOUND_KEY);
      }
    }
  });

  it('尺寸解析辅助函数与 schema 判定一致', () => {
    expect(parseImageSize('1024x1024')).toStrictEqual({
      height: 1024,
      width: 1024,
    });
    expect(parseImageSize('8192x1')).toStrictEqual({ height: 1, width: 8192 });
    expect(parseImageSize('8193x1')).toBeNull();
    expect(parseImageSize('0x1')).toBeNull();
    expect(parseImageSize('1024')).toBeNull();
    expect(parseImageSize('1024X1024')).toBeNull();
    expect(imageSizeSchema.parse('512x768')).toBe('512x768');
    expectRejected(imageSizeSchema.parse, '99999x99999');
    expectRejected(imageSizeSchema.parse, '1024x1024x2');
  });
});
