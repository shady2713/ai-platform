import type { MultimodalAvailability } from '@vben/ai-contracts';

import type {
  PrivateImageRef,
  VisionAttachmentApi,
} from '../vision-attachment';

import { mount } from '@vue/test-utils';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { default: ImageAttachmentCard } =
  await import('../ImageAttachmentCard.vue');

const IMAGE: PrivateImageRef = {
  fileId: 1024,
  mime: 'image/png',
  name: '合同扫描件.png',
  size: 2048,
};

function ocrResponse(text = '第 1 页识别文本') {
  return {
    capability: 'IMAGE_OCR',
    outcome: 'COMPLETED',
    result: {
      confidenceSource: 'UNKNOWN',
      fileId: 1024,
      kind: 'image_ocr',
      page: 1,
      regionSource: 'WHOLE_PAGE',
      reviewRequired: true,
      text,
      usage: { quantity: 33, source: 'REPORTED', unit: 'TOKEN' },
    },
    schemaVersion: '1.0',
  };
}

function mountCard(
  api?: VisionAttachmentApi,
  availability?: MultimodalAvailability,
) {
  return mount(ImageAttachmentCard, {
    props: { api, availability, file: IMAGE },
  });
}

describe('图片附件卡片', () => {
  beforeEach(() => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:preview'),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('未接入端口时只展示附件信息', () => {
    const wrapper = mountCard();

    expect(wrapper.get('[data-testid="ai-image-name"]').text()).toBe(
      '合同扫描件.png',
    );
    expect(wrapper.get('[data-testid="ai-image-summary"]').text()).toBe(
      '合同扫描件.png · 2 KB · image/png',
    );
    expect(wrapper.get('[data-testid="ai-image-readonly"]').text()).toBe(
      '未接入图片识别端口：仅展示附件信息',
    );
    expect(wrapper.find('[data-testid="ai-image-ocr-action"]').exists()).toBe(
      false,
    );
  });

  it('能力未开通时提示原因码且不提供识别动作', () => {
    const api: VisionAttachmentApi = {
      ocr: vi.fn(async () => ocrResponse()),
      readImage: vi.fn(async () => new Blob()),
      uploadImage: vi.fn(async () => IMAGE),
    };

    const wrapper = mountCard(api, {
      errorCode: '1003002007',
      message: '',
      reasonCode: 'CAPABILITY_NOT_ENABLED',
      state: 'UNAVAILABLE',
    });

    expect(wrapper.get('[data-testid="ai-image-unavailable"]').text()).toBe(
      '该端点未开通图片识别能力（需先声明并通过真实探测）（1003002007）',
    );
    expect(wrapper.find('[data-testid="ai-image-ocr-action"]').exists()).toBe(
      false,
    );
    expect(api.ocr).not.toHaveBeenCalled();
  });

  it('oCR 结果展示文本、页码/范围/置信度来源与未核验提示', async () => {
    const api: VisionAttachmentApi = {
      ocr: vi.fn(async () => ocrResponse()),
      readImage: vi.fn(async () => new Blob()),
      uploadImage: vi.fn(async () => IMAGE),
    };
    const wrapper = mountCard(api, { state: 'AVAILABLE' });

    await wrapper.get('[data-testid="ai-image-ocr-action"]').trigger('click');
    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="ai-image-ocr-text"]').exists()).toBe(
        true,
      ),
    );

    expect(wrapper.get('[data-testid="ai-image-ocr-text"]').text()).toBe(
      '第 1 页识别文本',
    );
    expect(
      wrapper.get('[data-testid="ai-image-ocr-provenance"]').text(),
    ).toContain('第 1 页');
    expect(
      wrapper.get('[data-testid="ai-image-ocr-provenance"]').text(),
    ).toContain('置信度：未提供（上游未返回，平台不猜测）');
    expect(
      wrapper.get('[data-testid="ai-image-ocr-provenance"]').text(),
    ).toContain('机器识别结果，未经人工核验');
    expect(wrapper.get('[data-testid="ai-image-ocr-usage"]').text()).toBe(
      '用量：33 TOKEN（上游提供）',
    );
    // 请求只带私有文件标识与声明元数据，且不含任何地址
    const request = (api.ocr as ReturnType<typeof vi.fn>).mock.calls[0]?.[0];
    expect(JSON.stringify(request)).not.toContain('http');
    expect(wrapper.html()).not.toContain('http');
  });

  it('预览失权时给固定提示，不回显宿主异常文本', async () => {
    const api: VisionAttachmentApi = {
      readImage: vi.fn(async () => {
        throw new Error('GET /ai/file/1024 403 token=secret-value');
      }),
      uploadImage: vi.fn(async () => IMAGE),
    };
    const wrapper = mountCard(api);

    await vi.waitFor(() =>
      expect(
        wrapper.find('[data-testid="ai-image-preview-error"]').exists(),
      ).toBe(true),
    );

    expect(wrapper.get('[data-testid="ai-image-preview-error"]').text()).toBe(
      '图片不可预览：无权限、已撤回或不存在',
    );
    expect(wrapper.html()).not.toContain('secret-value');
  });

  it('加载成功时渲染预览图，卸载时释放对象地址', async () => {
    const api: VisionAttachmentApi = {
      readImage: vi.fn(async () => new Blob()),
      uploadImage: vi.fn(async () => IMAGE),
    };
    const wrapper = mountCard(api);

    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="ai-image-preview"]').exists()).toBe(
        true,
      ),
    );
    expect(
      wrapper.get('[data-testid="ai-image-preview"]').attributes('src'),
    ).toBe('blob:preview');
    const revoke = URL.revokeObjectURL as ReturnType<typeof vi.fn>;

    wrapper.unmount();
    expect(revoke).toHaveBeenCalledWith('blob:preview');
  });

  it('识别失败时清空上一次结果并保留固定失败提示', async () => {
    const ocr = vi
      .fn()
      .mockResolvedValueOnce(ocrResponse('第一页文本'))
      .mockRejectedValueOnce(
        new Error('upstream url https://vendor.example.com/x failed'),
      );
    const api: VisionAttachmentApi = {
      ocr,
      readImage: vi.fn(async () => new Blob()),
      uploadImage: vi.fn(async () => IMAGE),
    };
    const wrapper = mountCard(api, { state: 'AVAILABLE' });

    await wrapper.get('[data-testid="ai-image-ocr-action"]').trigger('click');
    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="ai-image-ocr-text"]').exists()).toBe(
        true,
      ),
    );
    await wrapper.get('[data-testid="ai-image-ocr-action"]').trigger('click');
    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="ai-image-failure"]').exists()).toBe(
        true,
      ),
    );

    expect(wrapper.find('[data-testid="ai-image-ocr-text"]').exists()).toBe(
      false,
    );
    expect(wrapper.get('[data-testid="ai-image-failure"]').text()).toBe(
      '图片识别失败：请稍后重试，或改用文本输入',
    );
    expect(wrapper.html()).not.toContain('vendor.example.com');
  });

  it('响应为 UNAVAILABLE（终态）时展示平台原因码', async () => {
    const api: VisionAttachmentApi = {
      ocr: vi.fn(async () => ({
        capability: 'IMAGE_OCR',
        outcome: 'UNAVAILABLE',
        schemaVersion: '1.0',
        unavailable: {
          errorCode: '1003002007',
          message: '端点未开通该能力',
          reasonCode: 'CAPABILITY_NOT_ENABLED',
        },
      })),
      readImage: vi.fn(async () => new Blob()),
      uploadImage: vi.fn(async () => IMAGE),
    };
    const wrapper = mountCard(api, { state: 'AVAILABLE' });

    await wrapper.get('[data-testid="ai-image-ocr-action"]').trigger('click');
    await vi.waitFor(() =>
      expect(
        wrapper.find('[data-testid="ai-image-unavailable-outcome"]').exists(),
      ).toBe(true),
    );

    expect(
      wrapper.get('[data-testid="ai-image-unavailable-outcome"]').text(),
    ).toBe('端点未开通该能力（1003002007）');
  });

  it('图片理解结果只展示文本与用量', async () => {
    const api: VisionAttachmentApi = {
      readImage: vi.fn(async () => new Blob()),
      understand: vi.fn(async () => ({
        capability: 'IMAGE_UNDERSTANDING',
        outcome: 'COMPLETED',
        result: {
          fileId: 1024,
          kind: 'image_understanding',
          text: '图片是一份合同扫描件',
          usage: { quantity: null, source: 'UNKNOWN', unit: 'TOKEN' },
        },
        schemaVersion: '1.0',
      })),
      uploadImage: vi.fn(async () => IMAGE),
    };
    const wrapper = mountCard(api, { state: 'AVAILABLE' });

    await wrapper
      .get('[data-testid="ai-image-understand-action"]')
      .trigger('click');
    await vi.waitFor(() => expect(understandingText(wrapper)).not.toBeNull());

    expect(understandingText(wrapper)?.text()).toBe('图片是一份合同扫描件');
    expect(
      wrapper.get('[data-testid="ai-image-understand-usage"]').text(),
    ).toBe('用量：未提供');
  });
});

/** 识别结果文本：`<pre>` 只用 class 承载，定位靠结果容器 + 元素名。 */
function understandingText(wrapper: ReturnType<typeof mountCard>) {
  return wrapper.get('[data-testid="ai-image-understand-result"]').find('pre');
}
