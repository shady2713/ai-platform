import type {
  GeneratedImageAsset,
  ImageGenerationView,
} from '../image-generation';

import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ImageGenerationCard from '../ImageGenerationCard.vue';

const ASSET: GeneratedImageAsset = {
  fileId: 7,
  height: 768,
  mimeType: 'image/png',
  sizeBytes: 2048,
  width: 1024,
};

/** 尺寸未知的产物：宽高都是 null，界面必须说"尺寸未知"（不是 0×0）。 */
const UNKNOWN_SIZE_ASSET: GeneratedImageAsset = {
  fileId: 8,
  height: null,
  mimeType: 'image/jpeg',
  sizeBytes: 1024,
  width: null,
};

const SOURCE_FILE_ID = 12;

function view(
  overrides: Partial<ImageGenerationView> = {},
): ImageGenerationView {
  return {
    failureCode: null,
    images: [],
    progressPercent: null,
    prompt: '一只在海边的猫',
    requestKind: 'IMAGE_GENERATION',
    sourceFileId: null,
    status: 'RUNNING',
    usage: null,
    ...overrides,
  };
}

function succeeded(
  overrides: Partial<ImageGenerationView> = {},
): ImageGenerationView {
  return view({ images: [ASSET], status: 'SUCCEEDED', ...overrides });
}

function mountCard(
  cardView: ImageGenerationView,
  extra: {
    resolveFileUrl?: (fileId: number) => Promise<null | string>;
    sourceUnavailable?: boolean;
  } = {},
) {
  return mount(ImageGenerationCard, {
    props: { view: cardView, ...extra },
  });
}

interface PendingResolution {
  fail: (reason: unknown) => void;
  promise: Promise<null | string>;
  release: (value: null | string) => void;
}

function pendingResolution(): PendingResolution {
  let release: (value: null | string) => void = () => undefined;
  let fail: (reason: unknown) => void = () => undefined;
  const promise = new Promise<null | string>(
    (resolvePromise, rejectPromise) => {
      fail = rejectPromise;
      release = resolvePromise;
    },
  );
  return { fail, promise, release };
}

function texts(
  wrapper: ReturnType<typeof mountCard>,
  testId: string,
): string[] {
  return wrapper
    .findAll(`[data-testid="${testId}"]`)
    .map((node) => node.text());
}

afterEach(() => {
  vi.useRealTimers();
});

describe('图片生成/编辑卡片：状态', () => {
  it('排队中：显示排队状态，没有进度条也不摆产物占位', () => {
    const wrapper = mountCard(view({ status: 'QUEUED' }));
    expect(wrapper.get('[data-testid="ai-image-kind"]').text()).toBe(
      '图片生成',
    );
    expect(wrapper.get('[data-testid="ai-image-status"]').text()).toBe(
      '排队中',
    );
    expect(wrapper.find('[data-testid="ai-image-progress"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="ai-image-item"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="ai-image-empty"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="ai-image-failure"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="ai-image-cancel"]').exists()).toBe(true);
  });

  it('进行中但没有百分比：显示"进行中"，不显示假进度条与假百分比', () => {
    const wrapper = mountCard(
      view({ progressPercent: null, status: 'RUNNING' }),
    );
    expect(wrapper.get('[data-testid="ai-image-status"]').text()).toBe(
      '进行中',
    );
    expect(wrapper.find('[data-testid="ai-image-progress"]').exists()).toBe(
      false,
    );
    expect(wrapper.html()).not.toContain('%');
    expect(wrapper.find('[data-testid="ai-image-item"]').exists()).toBe(false);
  });

  it('进行中有百分比：进度条与百分比按后端值显示，越界值收敛到 0..100', async () => {
    const wrapper = mountCard(
      view({ progressPercent: 42.6, status: 'RUNNING' }),
    );
    expect(
      wrapper
        .get('[data-testid="ai-image-progress"] progress')
        .attributes('value'),
    ).toBe('43');
    expect(wrapper.get('[data-testid="ai-image-progress-text"]').text()).toBe(
      '43%',
    );

    await wrapper.setProps({ view: view({ progressPercent: 150 }) });
    expect(
      wrapper
        .get('[data-testid="ai-image-progress"] progress')
        .attributes('value'),
    ).toBe('100');
    expect(wrapper.get('[data-testid="ai-image-progress-text"]').text()).toBe(
      '100%',
    );
  });

  it('成功：逐图列出尺寸、大小与格式，尺寸未知显示"尺寸未知"而不是 0×0', () => {
    const wrapper = mountCard(
      succeeded({ images: [ASSET, UNKNOWN_SIZE_ASSET] }),
    );
    expect(wrapper.get('[data-testid="ai-image-status"]').text()).toBe(
      '已完成',
    );
    expect(wrapper.findAll('[data-testid="ai-image-item"]')).toHaveLength(2);
    expect(texts(wrapper, 'ai-image-dimension')).toStrictEqual([
      '1024 × 768',
      '尺寸未知',
    ]);
    expect(texts(wrapper, 'ai-image-size')).toStrictEqual(['2 KB', '1 KB']);
    expect(texts(wrapper, 'ai-image-mime')).toStrictEqual([
      'image/png',
      'image/jpeg',
    ]);
    expect(texts(wrapper, 'ai-image-file-id')).toStrictEqual([
      '文件 #7',
      '文件 #8',
    ]);
    expect(wrapper.html()).not.toContain('0 × 0');
    expect(wrapper.html()).not.toContain('0×0');
    expect(wrapper.find('[data-testid="ai-image-empty"]').exists()).toBe(false);
  });

  it('成功但没有产物：明确说明没有产物，不摆空占位也不冒充失败', () => {
    const wrapper = mountCard(succeeded({ images: [] }));
    expect(wrapper.get('[data-testid="ai-image-empty"]').text()).toBe(
      '任务已完成，但没有返回图片产物',
    );
    expect(wrapper.find('[data-testid="ai-image-item"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="ai-image-failure"]').exists()).toBe(
      false,
    );
  });

  it('失败：显示稳定错误码与"可重试"，不显示任何产物占位', () => {
    const wrapper = mountCard(
      view({ failureCode: '1003010004', status: 'FAILED' }),
    );
    expect(wrapper.get('[data-testid="ai-image-status"]').text()).toBe('失败');
    expect(wrapper.get('[data-testid="ai-image-failure-code"]').text()).toBe(
      '错误码：1003010004',
    );
    expect(wrapper.get('[data-testid="ai-image-retry-hint"]').text()).toContain(
      '可重试',
    );
    expect(wrapper.find('[data-testid="ai-image-item"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="ai-image-empty"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="ai-image-preview"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="ai-image-cancel"]').exists()).toBe(
      false,
    );
  });

  it('失败但后端没给错误码：不编造码，仍给"可重试"提示', () => {
    const wrapper = mountCard(view({ failureCode: null, status: 'FAILED' }));
    expect(wrapper.find('[data-testid="ai-image-failure-code"]').exists()).toBe(
      false,
    );
    expect(wrapper.get('[data-testid="ai-image-retry-hint"]').text()).toContain(
      '可重试',
    );

    const blank = mountCard(view({ failureCode: '   ', status: 'FAILED' }));
    expect(blank.find('[data-testid="ai-image-failure-code"]').exists()).toBe(
      false,
    );
  });

  it('已取消：显示已取消，不给取消入口也不摆失败/产物占位', () => {
    const wrapper = mountCard(view({ status: 'CANCELLED' }));
    expect(wrapper.get('[data-testid="ai-image-status"]').text()).toBe(
      '已取消',
    );
    expect(wrapper.find('[data-testid="ai-image-cancel"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="ai-image-failure"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="ai-image-item"]').exists()).toBe(false);
  });
});

describe('图片生成/编辑卡片：取消', () => {
  it.each(['SUCCEEDED', 'FAILED', 'CANCELLED'] as const)(
    '%s 是终态：不再提供取消操作',
    (status) => {
      const wrapper = mountCard(
        view({ images: [ASSET], status, failureCode: '1003010004' }),
      );
      expect(wrapper.find('[data-testid="ai-image-cancel"]').exists()).toBe(
        false,
      );
      expect(
        wrapper.find('[data-testid="ai-image-cancel-pending"]').exists(),
      ).toBe(false);
    },
  );

  it('进行中点击取消：只 emit 一次 cancel，之后按钮禁用并提示已请求', async () => {
    const wrapper = mountCard(view({ status: 'RUNNING' }));
    const button = wrapper.get('[data-testid="ai-image-cancel"]');
    await button.trigger('click');
    expect(wrapper.emitted('cancel')).toStrictEqual([[]]);
    expect(button.attributes('disabled')).toBeDefined();
    expect(wrapper.get('[data-testid="ai-image-cancel-pending"]').text()).toBe(
      '已请求取消，等待任务结束',
    );

    await button.trigger('click');
    expect(wrapper.emitted('cancel')).toStrictEqual([[]]);
  });

  it('同一 tick 内连点两次（禁用态尚未渲染）：也只 emit 一次 cancel', async () => {
    const wrapper = mountCard(view({ status: 'RUNNING' }));
    const button = wrapper.find<HTMLButtonElement>(
      '[data-testid="ai-image-cancel"]',
    );
    // 两次点击都在 Vue 重渲染之前发生：不能靠 DOM 的 disabled 兜底
    button.element.click();
    button.element.click();
    await nextTick();
    expect(wrapper.emitted('cancel')).toStrictEqual([[]]);
  });

  it('排队中同样可以取消', async () => {
    const wrapper = mountCard(view({ status: 'QUEUED' }));
    await wrapper.get('[data-testid="ai-image-cancel"]').trigger('click');
    expect(wrapper.emitted('cancel')).toStrictEqual([[]]);
  });
});

describe('图片生成/编辑卡片：下载与预览（只传 fileId）', () => {
  it('下载与预览只把 fileId 交给宿主，界面上不出现上游临时地址', async () => {
    // 上游数据里即使混进 URL，卡片也不认识这个字段、更不会渲染它
    const assetWithUpstreamUrl = Object.assign({}, ASSET, {
      url: 'https://upstream.invalid/tmp/7.png?sig=leaked',
    });
    const wrapper = mountCard(succeeded({ images: [assetWithUpstreamUrl] }));

    await wrapper.get('[data-testid="ai-image-download"]').trigger('click');
    await wrapper
      .get('[data-testid="ai-image-preview-action"]')
      .trigger('click');

    expect(wrapper.emitted('download')).toStrictEqual([[7]]);
    expect(wrapper.emitted('preview')).toStrictEqual([[7]]);
    expect(wrapper.html()).not.toContain('upstream.invalid');
    expect(wrapper.find('img').exists()).toBe(false);
  });

  it('注入 resolveFileUrl 时就地预览：回调只收到 fileId，展示平台端点解析结果', async () => {
    const resolveFileUrl = vi.fn(
      async (fileId: number) => `/ai/file/${fileId}/content`,
    );
    const wrapper = mountCard(succeeded(), { resolveFileUrl });

    await wrapper
      .get('[data-testid="ai-image-preview-action"]')
      .trigger('click');
    expect(resolveFileUrl).toHaveBeenCalledExactlyOnceWith(7);
    await flushPromises();

    expect(
      wrapper.get('[data-testid="ai-image-preview"]').attributes('src'),
    ).toBe('/ai/file/7/content');
    expect(
      wrapper.find('[data-testid="ai-image-preview-error"]').exists(),
    ).toBe(false);
  });

  it('解析进行中：同一产物的预览入口禁用，不重复发起解析', async () => {
    const pending = pendingResolution();
    const resolveFileUrl = vi.fn(() => pending.promise);
    const wrapper = mountCard(succeeded(), { resolveFileUrl });
    const button = wrapper.get('[data-testid="ai-image-preview-action"]');

    await button.trigger('click');
    expect(button.attributes('disabled')).toBeDefined();
    await button.trigger('click');
    expect(resolveFileUrl).toHaveBeenCalledTimes(1);

    pending.release('/ai/file/7/content');
    await flushPromises();
    expect(button.attributes('disabled')).toBeUndefined();
    expect(
      wrapper.get('[data-testid="ai-image-preview"]').attributes('src'),
    ).toBe('/ai/file/7/content');
  });

  it('解析失败：只给固定提示，不回显宿主异常文本，也不摆图片', async () => {
    const resolveFileUrl = vi.fn(async () => {
      throw new Error(
        'GET https://internal.invalid/ai/file/7?ticket=secret 已过期',
      );
    });
    const wrapper = mountCard(succeeded(), { resolveFileUrl });

    await wrapper
      .get('[data-testid="ai-image-preview-action"]')
      .trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-testid="ai-image-preview-error"]').text()).toBe(
      '图片不可预览：无权限、已撤回或不存在',
    );
    expect(wrapper.html()).not.toContain('secret');
    expect(wrapper.html()).not.toContain('internal.invalid');
    expect(wrapper.find('[data-testid="ai-image-preview"]').exists()).toBe(
      false,
    );
  });

  it('解析不到地址（null）同样按失败处理，不猜地址', async () => {
    const wrapper = mountCard(succeeded(), {
      resolveFileUrl: async () => null,
    });
    await wrapper
      .get('[data-testid="ai-image-preview-action"]')
      .trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="ai-image-preview-error"]').text()).toBe(
      '图片不可预览：无权限、已撤回或不存在',
    );
    expect(wrapper.find('img').exists()).toBe(false);
  });
});

describe('图片生成/编辑卡片：编辑模式与源图', () => {
  it('编辑模式：标注"基于源图编辑"并给出源图文件编号，产物照常展示', () => {
    const wrapper = mountCard(
      succeeded({ requestKind: 'IMAGE_EDIT', sourceFileId: SOURCE_FILE_ID }),
    );
    expect(wrapper.get('[data-testid="ai-image-kind"]').text()).toBe(
      '图片编辑',
    );
    const source = wrapper.get('[data-testid="ai-image-source"]');
    expect(source.text()).toContain('基于源图编辑');
    expect(source.text()).toContain(`#${SOURCE_FILE_ID}`);
    expect(wrapper.findAll('[data-testid="ai-image-item"]')).toHaveLength(1);
    expect(
      wrapper.find('[data-testid="ai-image-source-blocked"]').exists(),
    ).toBe(false);
  });

  it('编辑模式源图失权：给固定提示且不展示编辑产物', () => {
    const wrapper = mountCard(
      succeeded({ requestKind: 'IMAGE_EDIT', sourceFileId: SOURCE_FILE_ID }),
      { sourceUnavailable: true },
    );
    expect(wrapper.get('[data-testid="ai-image-source-blocked"]').text()).toBe(
      '源图不可读，无法编辑',
    );
    expect(wrapper.find('[data-testid="ai-image-source"]').exists()).toBe(
      false,
    );
    expect(wrapper.findAll('[data-testid="ai-image-item"]')).toHaveLength(0);
    expect(wrapper.find('[data-testid="ai-image-empty"]').exists()).toBe(false);
    expect(wrapper.find('img').exists()).toBe(false);
  });

  it('编辑模式没有源图编号：同样视为不可编辑，不展示产物', () => {
    const wrapper = mountCard(
      succeeded({ requestKind: 'IMAGE_EDIT', sourceFileId: null }),
    );
    expect(wrapper.get('[data-testid="ai-image-source-blocked"]').text()).toBe(
      '源图不可读，无法编辑',
    );
    expect(wrapper.find('[data-testid="ai-image-item"]').exists()).toBe(false);
  });

  it('生成模式不受源图失权标记影响（本就没有源图）', () => {
    const wrapper = mountCard(succeeded(), { sourceUnavailable: true });
    expect(
      wrapper.find('[data-testid="ai-image-source-blocked"]').exists(),
    ).toBe(false);
    expect(wrapper.find('[data-testid="ai-image-source"]').exists()).toBe(
      false,
    );
    expect(wrapper.findAll('[data-testid="ai-image-item"]')).toHaveLength(1);
  });
});

describe('图片生成/编辑卡片：用量', () => {
  it('用量未知（无用量数据或 quantity 为 null）显示"用量未知"，绝不显示 0', () => {
    expect(
      mountCard(view({ usage: null }))
        .get('[data-testid="ai-image-usage"]')
        .text(),
    ).toBe('用量：用量未知');
    expect(
      mountCard(view({ usage: { quantity: null, unit: 'IMAGE' } }))
        .get('[data-testid="ai-image-usage"]')
        .text(),
    ).toBe('用量：用量未知');
  });

  it('有用量时按 unit 显示实际数量', () => {
    const wrapper = mountCard(
      succeeded({ usage: { quantity: 3, unit: 'IMAGE' } }),
    );
    expect(wrapper.get('[data-testid="ai-image-usage"]').text()).toBe(
      '用量：3 IMAGE',
    );
  });
});

describe('图片生成/编辑卡片：销毁', () => {
  it('销毁后挂起的解析完成：不再发出事件，也不上报错误', async () => {
    vi.useFakeTimers();
    const errorHandler = vi.fn();
    const pending = pendingResolution();
    const resolveFileUrl = vi.fn(() => pending.promise);
    const wrapper = mount(ImageGenerationCard, {
      global: { config: { errorHandler } },
      props: { resolveFileUrl, view: succeeded() },
    });

    await wrapper
      .get('[data-testid="ai-image-preview-action"]')
      .trigger('click');
    expect(resolveFileUrl).toHaveBeenCalledExactlyOnceWith(7);
    wrapper.unmount();

    // 卸载后才结算：任何后续更新/事件都不得发生
    pending.release('/ai/file/7/content');
    await vi.advanceTimersByTimeAsync(1000);
    await flushPromises();

    // unmount 会清空事件记录：这里非空即说明销毁后仍有事件发出
    expect(wrapper.emitted()).toStrictEqual({});
    expect(errorHandler).not.toHaveBeenCalled();
  });

  it('销毁后挂起的解析被拒：无事件、无错误上报、无未处理的 Promise', async () => {
    vi.useFakeTimers();
    const errorHandler = vi.fn();
    const pending = pendingResolution();
    const wrapper = mount(ImageGenerationCard, {
      global: { config: { errorHandler } },
      props: {
        resolveFileUrl: vi.fn(() => pending.promise),
        view: succeeded(),
      },
    });

    await wrapper
      .get('[data-testid="ai-image-preview-action"]')
      .trigger('click');
    wrapper.unmount();

    // 事件处理器是 async 的：若解析异常穿透出去，Vue 会交给 errorHandler（或成为未处理拒绝）
    pending.fail(new Error('网络中断'));
    await vi.advanceTimersByTimeAsync(1000);
    await flushPromises();

    expect(wrapper.emitted()).toStrictEqual({});
    expect(errorHandler).not.toHaveBeenCalled();
  });
});
