import { describe, expect, it } from 'vitest';

import {
  isVoiceRegistered,
  MULTIMODAL_SCHEMA_VERSION,
  parseMultimodalCapabilityDescriptor,
  parseMultimodalRequest,
  parseMultimodalResponse,
} from '../index';

const SHA256 =
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

const IMAGE = {
  fileId: 7,
  height: 800,
  mime: 'image/png',
  name: 'chart.png',
  sha256: SHA256,
  size: 51_200,
  width: 1200,
};

const AUDIO = {
  durationMs: 12_000,
  fileId: 9,
  mime: 'audio/wav',
  name: 'voice.wav',
  sha256: SHA256,
  size: 256_000,
};

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

/** 结果与能力目录里绝不能出现的字段名：直链、换供应商、上游端点。 */
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

interface ContractSample {
  name: string;
  parse: (input: unknown) => unknown;
  value: unknown;
}

/**
 * 合法样例，且**带满可选字段**：注入测试要能在每个嵌套对象层级（包含数组元素里的对象）
 * 都验证一次"未知字段被拒绝"，样例缺可选字段就会漏掉那一层。
 */
const SAMPLES: ContractSample[] = [
  {
    name: 'IMAGE_UNDERSTANDING 请求',
    parse: parseMultimodalRequest,
    value: {
      capability: 'IMAGE_UNDERSTANDING',
      image: IMAGE,
      instruction: '这张图里有哪些指标？',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    },
  },
  {
    name: 'IMAGE_OCR 请求',
    parse: parseMultimodalRequest,
    value: {
      capability: 'IMAGE_OCR',
      image: IMAGE,
      includeRegions: true,
      languageHint: 'zh-CN',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    },
  },
  {
    name: 'IMAGE_GENERATION 请求',
    parse: parseMultimodalRequest,
    value: {
      capability: 'IMAGE_GENERATION',
      count: 2,
      outputFormat: 'webp',
      prompt: '一张销售看板配图',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      size: '1024x1024',
    },
  },
  {
    name: 'IMAGE_EDIT 请求',
    parse: parseMultimodalRequest,
    value: {
      capability: 'IMAGE_EDIT',
      instruction: '把背景改成浅灰',
      outputFormat: 'png',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      size: '1536x1024',
      source: IMAGE,
    },
  },
  {
    name: 'SPEECH_TO_TEXT 请求',
    parse: parseMultimodalRequest,
    value: {
      audio: AUDIO,
      capability: 'SPEECH_TO_TEXT',
      languageHint: 'zh-CN',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    },
  },
  {
    name: 'TEXT_TO_SPEECH 请求',
    parse: parseMultimodalRequest,
    value: {
      capability: 'TEXT_TO_SPEECH',
      language: 'zh-CN',
      outputFormat: 'opus',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      text: '本月销售额已完成目标。',
      voiceId: 'zh-CN-female-01',
    },
  },
  {
    name: 'IMAGE_UNDERSTANDING 结果',
    parse: parseMultimodalResponse,
    value: {
      capability: 'IMAGE_UNDERSTANDING',
      outcome: 'COMPLETED',
      result: {
        kind: 'image_understanding',
        text: '图中是月度销售趋势。',
        usage: { quantity: 512, source: 'REPORTED', unit: 'TOKEN' },
      },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    },
  },
  {
    name: 'IMAGE_OCR 结果',
    parse: parseMultimodalResponse,
    value: {
      capability: 'IMAGE_OCR',
      outcome: 'COMPLETED',
      result: {
        kind: 'image_ocr',
        regions: [
          {
            bounds: { height: 0.1, width: 0.2, x: 0.5, y: 0.4 },
            confidence: 0.87,
            text: '合计 100',
          },
        ],
        text: '合计 100',
        usage: { quantity: 1, source: 'REPORTED', unit: 'IMAGE' },
      },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    },
  },
  {
    name: 'IMAGE_GENERATION 结果',
    parse: parseMultimodalResponse,
    value: {
      capability: 'IMAGE_GENERATION',
      outcome: 'COMPLETED',
      result: {
        files: [IMAGE_FILE],
        kind: 'image_generation',
        usage: {
          currency: 'CNY',
          estimatedCost: '0.35',
          quantity: 1,
          source: 'REPORTED',
          unit: 'IMAGE',
        },
      },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    },
  },
  {
    name: 'IMAGE_EDIT 结果',
    parse: parseMultimodalResponse,
    value: {
      capability: 'IMAGE_EDIT',
      outcome: 'COMPLETED',
      result: {
        files: [IMAGE_FILE],
        kind: 'image_edit',
        usage: { quantity: 1, source: 'REPORTED', unit: 'IMAGE' },
      },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    },
  },
  {
    name: 'SPEECH_TO_TEXT 结果',
    parse: parseMultimodalResponse,
    value: {
      capability: 'SPEECH_TO_TEXT',
      outcome: 'COMPLETED',
      result: {
        kind: 'speech_to_text',
        segments: [
          { endMillis: 3000, startMillis: 0, text: '你好' },
          { endMillis: 6000, startMillis: 3000, text: '世界' },
        ],
        text: '你好世界',
        usage: {
          quantity: 6000,
          source: 'REPORTED',
          unit: 'AUDIO_MILLISECOND',
        },
      },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    },
  },
  {
    name: 'TEXT_TO_SPEECH 结果',
    parse: parseMultimodalResponse,
    value: {
      capability: 'TEXT_TO_SPEECH',
      outcome: 'COMPLETED',
      result: {
        file: AUDIO_FILE,
        kind: 'text_to_speech',
        usage: { quantity: 11, source: 'REPORTED', unit: 'CHARACTER' },
        voiceId: 'zh-CN-female-01',
      },
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
    },
  },
  {
    name: 'TEXT_TO_SPEECH 能力目录',
    parse: parseMultimodalCapabilityDescriptor,
    value: {
      availability: { state: 'AVAILABLE' },
      capability: 'TEXT_TO_SPEECH',
      execution: 'SYNC',
      languages: ['zh-CN'],
      voices: ['zh-CN-female-01'],
    },
  },
];

/** 遍历样例里的每个对象节点（含数组元素内的对象）。 */
function forEachObjectNode(
  value: unknown,
  visit: (node: Record<string, unknown>) => void,
): void {
  if (Array.isArray(value)) {
    for (const item of value) {
      forEachObjectNode(item, visit);
    }
    return;
  }
  if (typeof value !== 'object' || value === null) {
    return;
  }
  const node = value as Record<string, unknown>;
  visit(node);
  for (const child of Object.values(node)) {
    forEachObjectNode(child, visit);
  }
}

/** 复制样例，并只在 `target` 这个对象节点上追加额外字段（其余节点保持原样）。 */
function injectField(
  value: unknown,
  target: Record<string, unknown>,
  extra: Record<string, unknown>,
): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => injectField(item, target, extra));
  }
  if (typeof value !== 'object' || value === null) {
    return value;
  }
  const node = value as Record<string, unknown>;
  const clone: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(node)) {
    clone[key] = injectField(child, target, extra);
  }
  return node === target ? { ...clone, ...extra } : clone;
}

describe('多模态外发护栏（X01 / AT-068）', () => {
  it('每个嵌套层级都拒绝直链/换供应商/换模型字段', () => {
    for (const { name, parse, value } of SAMPLES) {
      let visited = 0;
      forEachObjectNode(value, (node) => {
        visited += 1;
        for (const field of FORBIDDEN_FIELDS) {
          const injected = injectField(value, node, {
            [field]:
              field === 'modelId' ? 42 : 'https://upstream.example.com/v1',
          });
          expect(
            () => parse(injected),
            `${name} 的字段 ${field} 必须被拒绝`,
          ).toThrow();
        }
      });
      expect(visited, `${name} 的样例必须含至少一个对象节点`).toBeGreaterThan(
        0,
      );
    }
  });

  it('解析后的字段名里没有直链/供应商/降级线索', () => {
    for (const { name, parse, value } of SAMPLES) {
      forEachObjectNode(parse(value), (node) => {
        for (const key of Object.keys(node)) {
          expect(key, `${name} 出现了字段 ${key}`).not.toMatch(OUTBOUND_KEY);
        }
      });
    }
  });

  it('不可用（UNAVAILABLE）是终态：不得夹带结果或产物（无静默替代）', () => {
    const unavailable = {
      capability: 'IMAGE_GENERATION',
      outcome: 'UNAVAILABLE',
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      unavailable: {
        errorCode: '1003002007',
        message: '当前服务未开通图片生成能力',
        reasonCode: 'CAPABILITY_NOT_ENABLED',
      },
    };
    expect(parseMultimodalResponse(unavailable).outcome).toBe('UNAVAILABLE');
    expect(() =>
      parseMultimodalResponse({
        ...unavailable,
        result: { files: [IMAGE_FILE], kind: 'image_generation' },
      }),
    ).toThrow();
    expect(() =>
      parseMultimodalResponse({ ...unavailable, files: [IMAGE_FILE] }),
    ).toThrow();
  });

  it('受理态（ACCEPTED）不得夹带结果', () => {
    const accepted = {
      capability: 'IMAGE_EDIT',
      outcome: 'ACCEPTED',
      runId: 42,
      schemaVersion: MULTIMODAL_SCHEMA_VERSION,
      status: 'RUNNING',
    };
    expect(parseMultimodalResponse(accepted).outcome).toBe('ACCEPTED');
    expect(() =>
      parseMultimodalResponse({
        ...accepted,
        result: { files: [IMAGE_FILE], kind: 'image_edit' },
      }),
    ).toThrow();
  });

  it('音色白名单：未登记音色不得使用，也不得猜测供应商音色名', () => {
    const descriptor = parseMultimodalCapabilityDescriptor({
      availability: { state: 'AVAILABLE' },
      capability: 'TEXT_TO_SPEECH',
      execution: 'SYNC',
      languages: ['zh-CN'],
      voices: ['zh-CN-female-01', 'zh-CN-male-01'],
    });
    expect(isVoiceRegistered(descriptor, 'zh-CN-female-01')).toBe(true);
    expect(isVoiceRegistered(descriptor, 'zh-CN-male-01')).toBe(true);
    // 大小写敏感、不做前缀匹配、不放行供应商私有名字
    expect(isVoiceRegistered(descriptor, 'ZH-CN-FEMALE-01')).toBe(false);
    expect(isVoiceRegistered(descriptor, 'zh-CN-female')).toBe(false);
    expect(isVoiceRegistered(descriptor, 'vendor-b-voice')).toBe(false);

    // 不可用且未登记音色的目录：任何音色都不是"已登记"
    const unavailable = parseMultimodalCapabilityDescriptor({
      availability: {
        errorCode: '1003002007',
        message: '当前服务未开通语音合成能力',
        reasonCode: 'CAPABILITY_NOT_ENABLED',
        state: 'UNAVAILABLE',
      },
      capability: 'TEXT_TO_SPEECH',
      execution: 'SYNC',
    });
    expect(isVoiceRegistered(unavailable, 'zh-CN-female-01')).toBe(false);

    // 非 TTS 能力不登记音色
    const ocr = parseMultimodalCapabilityDescriptor({
      availability: { state: 'AVAILABLE' },
      capability: 'IMAGE_OCR',
      execution: 'SYNC',
      languages: ['zh-CN'],
    });
    expect(isVoiceRegistered(ocr, 'zh-CN-female-01')).toBe(false);
  });
});
