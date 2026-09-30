import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createMappingEntry,
  createRevision,
  deleteMappingEntry,
  getRevisionDetail,
  getRevisionPage,
  publishRevision,
} from '#/api/ai/semantic';
import { showRequestError, showSuccessMessage } from '#/utils/feedback';

import Versions from './versions.vue';

interface ModalConfig {
  onOpenChange: (isOpen: boolean) => Promise<void> | void;
}

const state = vi.hoisted(() => ({
  entryFormApi: {
    getValues: vi.fn(),
    resetForm: vi.fn(),
    validate: vi.fn(),
  },
  modalApi: {
    getData: vi.fn(),
    lock: vi.fn(),
    setState: vi.fn(),
    unlock: vi.fn(),
  },
  modalConfig: undefined as ModalConfig | undefined,
  revisionFormApi: {
    getValues: vi.fn(),
    resetForm: vi.fn(),
    validate: vi.fn(),
  },
  formCall: 0,
}));

vi.mock('@vben/common-ui', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/common-ui')>('@vben/common-ui');
  const { defineComponent, h } = await import('vue');
  return {
    z: actual.z,
    useVbenModal: vi.fn((config: ModalConfig) => {
      state.modalConfig = config;
      return [
        defineComponent({
          name: 'ModalStub',
          setup(_props, { slots }) {
            return () => h('section', slots.default?.());
          },
        }),
        state.modalApi,
      ];
    }),
  };
});

vi.mock('#/adapter/form', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenForm: vi.fn(() => {
      state.formCall += 1;
      return [
        defineComponent({
          name: `FormStub${state.formCall}`,
          setup(_props, { slots }) {
            return () => h('form', slots.default?.());
          },
        }),
        state.formCall === 1 ? state.revisionFormApi : state.entryFormApi,
      ];
    }),
  };
});

vi.mock('#/api/ai/semantic', () => ({
  createMappingEntry: vi.fn(),
  createRevision: vi.fn(),
  deleteMappingEntry: vi.fn(),
  getRevisionDetail: vi.fn(),
  getRevisionPage: vi.fn(),
  publishRevision: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showRequestError: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

function revisionDetail(publishable: boolean) {
  return {
    conflictKeys: publishable ? [] : ['5/7/customer'],
    entries: [
      {
        applicationId: 7,
        entityType: 'customer',
        id: 77,
        matchMethod: 'MANUAL',
        problem: publishable ? 'NONE' : 'CONFLICT',
        sourceKey: 'C-1001',
        sourceName: '杭州云启科技有限公司',
        validFrom: '2026-01-01T00:00:00',
        version: 0,
      },
    ],
    publishable,
    revision: {
      createdBy: 1001,
      entryCount: 1,
      mappingFingerprint: 'fingerprint',
      masterObjectId: 5,
      revisionNo: 1,
      status: 'DRAFT',
      validFrom: '2026-01-01T00:00:00',
      version: 0,
    },
  };
}

async function render() {
  const wrapper = mount(Versions);
  await state.modalConfig?.onOpenChange(true);
  await flushPromises();
  return wrapper;
}

describe('映射版本弹窗（Y02）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.formCall = 0;
    state.modalConfig = undefined;
    state.modalApi.getData.mockReturnValue({
      currentRevision: 1,
      id: 5,
      objectCode: 'md_cloud_qi',
      objectName: '云启科技（统一客户）',
      objectType: 'CUSTOMER',
      status: 'ACTIVE',
      version: 3,
    });
    state.revisionFormApi.validate.mockResolvedValue({ valid: true });
    state.revisionFormApi.getValues.mockResolvedValue({
      validFrom: '2026-01-01T00:00:00',
    });
    state.entryFormApi.validate.mockResolvedValue({ valid: true });
    state.entryFormApi.getValues.mockResolvedValue({
      applicationId: 7,
      entityType: 'customer',
      matchMethod: 'MANUAL',
      sourceKey: ' C-1001 ',
      sourceName: '杭州云启科技有限公司',
      validFrom: '2026-01-01T00:00:00',
    });
    vi.mocked(getRevisionPage).mockResolvedValue({
      list: [
        {
          createdBy: 1001,
          entryCount: 1,
          mappingFingerprint: 'fingerprint',
          masterObjectId: 5,
          revisionNo: 1,
          status: 'DRAFT',
          validFrom: '2026-01-01T00:00:00',
          version: 0,
        },
      ],
      total: 1,
    });
    vi.mocked(getRevisionDetail).mockResolvedValue(
      revisionDetail(true) as never,
    );
    vi.mocked(createRevision).mockResolvedValue(2);
    vi.mocked(createMappingEntry).mockResolvedValue(78);
    vi.mocked(deleteMappingEntry).mockResolvedValue(true);
    vi.mocked(publishRevision).mockResolvedValue({} as never);
  });

  it('草稿的条目与问题如实展示（冲突不隐藏）', async () => {
    vi.mocked(getRevisionDetail).mockResolvedValue(
      revisionDetail(false) as never,
    );
    const wrapper = await render();
    const text = wrapper.text();

    expect(text).toContain('C-1001');
    expect(text).toContain('冲突（一对多/多对一，必须人工处理）');
    expect(text).toContain('冲突键：5/7/customer');
    expect(text).toContain('当前版本不可发布（草稿未建/无条目/存在冲突）');
    expect(
      (wrapper.get('[data-test="publish"]').element as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('登记源键提交草稿版本号并去掉首尾空白', async () => {
    const wrapper = await render();

    await wrapper.get('[data-test="add-entry"]').trigger('click');
    await flushPromises();

    expect(createMappingEntry).toHaveBeenCalledWith({
      applicationId: 7,
      entityType: 'customer',
      masterObjectId: 5,
      matchMethod: 'MANUAL',
      revisionNo: 1,
      sourceKey: 'C-1001',
      sourceName: '杭州云启科技有限公司',
      validFrom: '2026-01-01T00:00:00',
      validTo: undefined,
    });
    expect(showSuccessMessage).toHaveBeenCalledWith(
      '已登记源键（同名不会自动合并）',
    );
  });

  it('可发布版本发布成功；发布被拒时提示处理冲突', async () => {
    const wrapper = await render();

    await wrapper.get('[data-test="publish"]').trigger('click');
    await flushPromises();
    expect(publishRevision).toHaveBeenCalledWith(5, 1, 0);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      '版本已发布：内容与指纹已冻结，判定按该版本解释',
    );

    vi.mocked(publishRevision).mockRejectedValueOnce(new Error('conflict'));
    await wrapper.get('[data-test="publish"]').trigger('click');
    await flushPromises();
    expect(showRequestError).toHaveBeenCalledWith(
      expect.any(Error),
      '发布被拒绝：请先处理冲突/过期映射',
    );
  });

  it('新建草稿提交版本有效期并重新加载', async () => {
    const wrapper = await render();
    vi.mocked(getRevisionDetail).mockClear();

    await wrapper.get('[data-test="create-revision"]').trigger('click');
    await flushPromises();

    expect(createRevision).toHaveBeenCalledWith({
      masterObjectId: 5,
      validFrom: '2026-01-01T00:00:00',
      validTo: undefined,
    });
    expect(showSuccessMessage).toHaveBeenCalledWith(
      '已创建草稿版本 v2，请登记源键',
    );
    expect(getRevisionPage).toHaveBeenCalled();
  });

  it('删除草稿条目按乐观锁版本提交（已发布版本由后端拒绝）', async () => {
    const wrapper = await render();

    await wrapper.get('li[data-test="entry-77"] button').trigger('click');
    await flushPromises();

    expect(deleteMappingEntry).toHaveBeenCalledWith(77, 0);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      '条目已删除（仅草稿可编辑）',
    );
  });

  it('登记失败（如已发布版本/重复源键）按 409 文案提示', async () => {
    vi.mocked(createMappingEntry).mockRejectedValueOnce(new Error('duplicate'));
    const wrapper = await render();

    await wrapper.get('[data-test="add-entry"]').trigger('click');
    await flushPromises();

    expect(showRequestError).toHaveBeenCalledWith(
      expect.any(Error),
      '登记被拒绝',
    );
  });
});
