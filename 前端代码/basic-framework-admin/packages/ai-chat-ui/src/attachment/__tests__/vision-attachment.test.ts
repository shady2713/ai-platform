import type {
  MultimodalAvailability,
  MultimodalUnavailableReason,
} from '@vben/ai-contracts';

import type {
  ImageUploadCandidate,
  PrivateImageRef,
  VisionAttachmentApi,
} from '../vision-attachment';

import { describe, expect, it, vi } from 'vitest';

import {
  availabilityNoticeText,
  capabilityUsable,
  checkImageUpload,
  failureFromThrown,
  IMAGE_PREVIEW_FAILURE_MESSAGE,
  imageAttachmentSummary,
  interpretVisionResponse,
  ocrProvenanceText,
  regionText,
  resultReferenceSummary,
  unavailableErrorCode,
  unavailableReasonMessage,
  uploadRejectionMessage,
  usageText,
  VISION_FAILURE_MESSAGE,
} from '../vision-attachment';

const IMAGE: PrivateImageRef = {
  fileId: 1024,
  mime: 'image/png',
  name: '合同扫描件.png',
  size: 2048,
};

function completedOcr(payload: Record<string, unknown> = {}) {
  return {
    capability: 'IMAGE_OCR',
    outcome: 'COMPLETED',
    result: {
      fileId: 1024,
      kind: 'image_ocr',
      page: 3,
      regionSource: 'WHOLE_PAGE',
      confidenceSource: 'UNKNOWN',
      reviewRequired: true,
      text: '第 3 页识别文本',
      usage: { quantity: 33, source: 'REPORTED', unit: 'TOKEN' },
      ...payload,
    },
    schemaVersion: '1.0',
  };
}

describe('图片附件上传校验', () => {
  it('只接受白名单格式与非空体积，超限给固定文案', () => {
    const tooLarge: ImageUploadCandidate = {
      mime: 'image/png',
      name: 'big.png',
      size: 11 * 1024 * 1024,
    };
    expect(
      checkImageUpload({ mime: 'image/png', name: 'ok.png', size: 10 }).ok,
    ).toBe(true);
    expect(
      checkImageUpload({ mime: 'image/svg+xml', name: 'x.svg', size: 10 }),
    ).toStrictEqual({
      message: '只支持 PNG / JPEG / WebP 图片',
      ok: false,
      reason: 'UNSUPPORTED_MIME',
    });
    expect(
      checkImageUpload({ mime: 'image/png', name: 'x.png', size: 0 }),
    ).toStrictEqual({
      message: '图片内容为空，无法上传',
      ok: false,
      reason: 'EMPTY',
    });
    expect(checkImageUpload(tooLarge)).toStrictEqual({
      message: '图片超过 10 MiB 上限',
      ok: false,
      reason: 'TOO_LARGE',
    });
    expect(uploadRejectionMessage('TOO_LARGE')).toBe('图片超过 10 MiB 上限');
    expect(uploadRejectionMessage('UNSUPPORTED_MIME')).toBe(
      '只支持 PNG / JPEG / WebP 图片',
    );
    expect(uploadRejectionMessage('EMPTY')).toBe('图片内容为空，无法上传');
  });

  it('文件名清洗后进入引用（只用于显示，不参与请求）', () => {
    const checked = checkImageUpload({
      mime: 'IMAGE/PNG',
      name: '../../etc/passwd',
      size: 10,
    });
    expect(checked).toStrictEqual({
      file: { mime: 'image/png', name: 'passwd', size: 10 },
      ok: true,
    });
  });
});

describe('图片识别结果解析与展示', () => {
  it('oCR 结果带页码、范围与置信度来源，且引用只有私有文件标识', () => {
    const outcome = interpretVisionResponse(completedOcr());

    expect(outcome.state).toBe('OK');
    if (outcome.state !== 'OK') {
      return;
    }
    expect(outcome.fileId).toBe(1024);
    expect(outcome.page).toBe(3);
    expect(outcome.regionSource).toBe('WHOLE_PAGE');
    expect(outcome.confidenceSource).toBe('UNKNOWN');
    expect(outcome.reviewRequired).toBe(true);
    expect(outcome.text).toBe('第 3 页识别文本');
    expect(ocrProvenanceText(outcome)).toContain('第 3 页');
    expect(ocrProvenanceText(outcome)).toContain(
      '置信度：未提供（上游未返回，平台不猜测）',
    );
    expect(ocrProvenanceText(outcome)).toContain('机器识别结果，未经人工核验');
    expect(usageText(outcome.usage)).toBe('用量：33 TOKEN（上游提供）');
    expect(resultReferenceSummary(outcome, IMAGE.name)).toBe(
      '合同扫描件.png (#1024)',
    );
    // 结果里没有任何地址字段，序列化后也不含协议前缀
    expect(Object.keys(outcome)).not.toContain('url');
    expect(JSON.stringify(outcome)).not.toContain('http');
  });

  it('上游给出逐块区域时区域可展示；区域越界视为没有区域', () => {
    const withRegions = interpretVisionResponse(
      completedOcr({
        confidenceSource: 'PROVIDER',
        regionSource: 'PROVIDER',
        regions: [
          {
            confidence: 0.93,
            height: 0.1,
            text: '金额',
            width: 0.2,
            x: 0.1,
            y: 0.2,
          },
        ],
      }),
    );
    expect(withRegions.state).toBe('OK');
    if (withRegions.state === 'OK') {
      expect(withRegions.regions).toHaveLength(1);
      expect(ocrProvenanceText(withRegions)).toContain('置信度：上游提供');
      const region = withRegions.regions?.[0];
      expect(region).toBeDefined();
      if (region !== undefined) {
        expect(regionText(region)).toContain('置信度 0.93');
      }
    }

    const outOfBounds = interpretVisionResponse(
      completedOcr({
        confidenceSource: 'PROVIDER',
        regions: [
          {
            confidence: 0.5,
            height: 0.5,
            text: 'x',
            width: 0.9,
            x: 0.5,
            y: 0.5,
          },
        ],
      }),
    );
    expect(outOfBounds.state).toBe('OK');
    if (outOfBounds.state === 'OK') {
      expect(outOfBounds.regions).toBeUndefined();
    }
  });

  it('形状不合法的响应按失败处理，不部分渲染', () => {
    expect(interpretVisionResponse(undefined).state).toBe('FAILED');
    expect(
      interpretVisionResponse({ outcome: 'COMPLETED', result: {} }).state,
    ).toBe('FAILED');
    expect(interpretVisionResponse(completedOcr({ text: '   ' })).state).toBe(
      'FAILED',
    );
    // 图片理解与 OCR 是同步能力：受理态属协议异常
    expect(
      interpretVisionResponse({
        capability: 'IMAGE_OCR',
        outcome: 'ACCEPTED',
        runId: 1,
      }).state,
    ).toBe('FAILED');
  });

  it('用量缺失显示未提供，不用 0 冒充实测', () => {
    const outcome = interpretVisionResponse(
      completedOcr({
        usage: { quantity: null, source: 'UNKNOWN', unit: 'TOKEN' },
      }),
    );
    expect(outcome.state).toBe('OK');
    if (outcome.state === 'OK') {
      expect(outcome.usage).toStrictEqual({
        quantity: null,
        source: 'UNKNOWN',
        unit: 'TOKEN',
      });
      expect(usageText(outcome.usage)).toBe('用量：未提供');
    }
    expect(usageText(undefined)).toBe('用量：未提供');
    expect(
      usageText({ quantity: 12, source: 'ESTIMATED', unit: 'TOKEN' }),
    ).toBe('用量：12 TOKEN（平台估算）');
  });
});

describe('能力不可用与失败态', () => {
  const unavailable = (
    reasonCode: MultimodalUnavailableReason,
  ): MultimodalAvailability => ({
    errorCode: unavailableErrorCode(reasonCode),
    message: '',
    reasonCode,
    state: 'UNAVAILABLE',
  });

  it('未开通能力给出稳定原因码与错误码，不静默降级', () => {
    expect(
      availabilityNoticeText(
        'IMAGE_OCR',
        unavailable('CAPABILITY_NOT_ENABLED'),
      ),
    ).toBe('该端点未开通图片识别能力（需先声明并通过真实探测）（1003002007）');
    expect(capabilityUsable(unavailable('CAPABILITY_NOT_ENABLED'))).toBe(false);
    expect(capabilityUsable({ state: 'AVAILABLE' })).toBe(true);
    expect(availabilityNoticeText('IMAGE_OCR', { state: 'AVAILABLE' })).toBe(
      '',
    );
    expect(availabilityNoticeText('IMAGE_OCR', undefined)).toBe('');
    expect(unavailableReasonMessage('ENDPOINT_DISABLED')).toBe(
      '该端点已停用，请联系管理员',
    );
  });

  it('响应里的 UNAVAILABLE 是终态，原样展示平台原因', () => {
    const outcome = interpretVisionResponse({
      capability: 'IMAGE_OCR',
      outcome: 'UNAVAILABLE',
      schemaVersion: '1.0',
      unavailable: {
        errorCode: '1003002007',
        message: '',
        reasonCode: 'CAPABILITY_NOT_ENABLED',
      },
    });

    expect(outcome).toStrictEqual({
      capability: 'IMAGE_OCR',
      errorCode: '1003002007',
      message: '该端点未开通图片识别能力（需先声明并通过真实探测）',
      reasonCode: 'CAPABILITY_NOT_ENABLED',
      state: 'UNAVAILABLE',
    });
  });

  it('宿主异常只映射固定提示，不回显异常文本', () => {
    const failed = failureFromThrown(
      'IMAGE_OCR',
      new Error('POST /ai/vision/image/ocr 403 token=secret-value'),
    );

    expect(failed).toStrictEqual({
      capability: 'IMAGE_OCR',
      errorCode: null,
      message: VISION_FAILURE_MESSAGE,
      state: 'FAILED',
    });
    expect(JSON.stringify(failed)).not.toContain('secret-value');

    // 宿主按平台错误码包装的结构化失败会被识别（错误码保留、文案仍固定）
    expect(
      failureFromThrown('IMAGE_OCR', {
        errorCode: '1003010001',
        state: 'FAILED',
      }),
    ).toStrictEqual({
      capability: 'IMAGE_OCR',
      errorCode: '1003010001',
      message: VISION_FAILURE_MESSAGE,
      state: 'FAILED',
    });
    // 未知的原因码不被猜测
    expect(
      failureFromThrown('IMAGE_OCR', {
        reasonCode: 'SOMETHING_NEW',
        state: 'UNAVAILABLE',
      }).state,
    ).toBe('FAILED');
  });

  it('附件摘要与预览失败文案是固定文本', () => {
    expect(imageAttachmentSummary(IMAGE)).toBe(
      '合同扫描件.png · 2 KB · image/png',
    );
    expect(IMAGE_PREVIEW_FAILURE_MESSAGE).toBe(
      '图片不可预览：无权限、已撤回或不存在',
    );
  });
});

describe('上传端口契约', () => {
  it('上传只回私有引用；识别请求只带文件编号与声明元数据', async () => {
    const api: VisionAttachmentApi = {
      ocr: vi.fn(async () => completedOcr()),
      readImage: vi.fn(async () => new Blob()),
      uploadImage: vi.fn(async () => IMAGE),
    };

    const uploaded = await api.uploadImage({
      mime: 'image/png',
      name: 'x.png',
      size: 10,
    });
    const response = await api.ocr?.({
      image: uploaded,
      languageHint: 'zh-CN',
    });
    const request = (api.ocr as ReturnType<typeof vi.fn>).mock.calls[0]?.[0];

    expect(Object.keys(uploaded)).not.toContain('url');
    expect(JSON.stringify(request)).not.toContain('http');
    expect(interpretVisionResponse(response).state).toBe('OK');
  });
});
