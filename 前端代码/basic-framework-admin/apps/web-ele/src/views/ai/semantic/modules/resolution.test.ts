import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getMasterObjectCatalog,
  resolveObjectKey,
  resolveSourceKey,
} from '#/api/ai/semantic';
import { showRequestError } from '#/utils/feedback';

import Resolution from './resolution.vue';

interface ModalConfig {
  onOpenChange: (isOpen: boolean) => Promise<void> | void;
}

const state = vi.hoisted(() => ({
  catalogFormApi: { getValues: vi.fn(), setValues: vi.fn() },
  modalApi: {
    getData: vi.fn(),
    lock: vi.fn(),
    setState: vi.fn(),
    unlock: vi.fn(),
  },
  modalConfig: undefined as ModalConfig | undefined,
  resolveFormApi: { getValues: vi.fn(), setValues: vi.fn() },
  reverseFormApi: { getValues: vi.fn(), setValues: vi.fn() },
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
      const apis = [
        state.resolveFormApi,
        state.reverseFormApi,
        state.catalogFormApi,
      ];
      return [
        defineComponent({
          name: `FormStub${state.formCall}`,
          setup(_props, { slots }) {
            return () => h('form', slots.default?.());
          },
        }),
        apis[state.formCall - 1],
      ];
    }),
  };
});

vi.mock('#/api/ai/semantic', () => ({
  getMasterObjectCatalog: vi.fn(),
  resolveObjectKey: vi.fn(),
  resolveSourceKey: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showRequestError: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

async function render() {
  const wrapper = mount(Resolution);
  await state.modalConfig?.onOpenChange(true);
  await flushPromises();
  return wrapper;
}

describe('判定与目录弹窗（Y02）', () => {
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
    state.resolveFormApi.getValues.mockResolvedValue({
      applicationId: 7,
      entityType: 'customer',
      revisionNo: 1,
    });
    state.reverseFormApi.getValues.mockResolvedValue({
      applicationId: 7,
      entityType: 'customer',
      sourceKey: 'C-1003',
    });
    state.catalogFormApi.getValues.mockResolvedValue({
      applicationId: 7,
      externalUserId: 'alice',
      subjectType: 'USER',
    });
  });

  it('判定结果显示版本与冻结指纹（按显式版本解释）', async () => {
    vi.mocked(resolveObjectKey).mockResolvedValue({
      applicationId: 7,
      asOf: '2026-09-01T00:00:00',
      entityType: 'customer',
      matchMethod: 'MANUAL',
      masterObjectId: 5,
      objectCode: 'md_cloud_qi',
      objectName: '云启科技（统一客户）',
      objectType: 'CUSTOMER',
      revisionFingerprint: 'fingerprint',
      revisionNo: 1,
      sourceKey: 'C-1001',
      sourceName: '杭州云启科技有限公司',
      validFrom: '2026-01-01T00:00:00',
    });
    const wrapper = await render();

    await wrapper.get('[data-test="resolve"]').trigger('click');
    await flushPromises();

    expect(resolveObjectKey).toHaveBeenCalledWith(
      expect.objectContaining({
        applicationId: 7,
        entityType: 'customer',
        objectCode: 'md_cloud_qi',
        revisionNo: 1,
      }),
    );
    expect(wrapper.get('[data-test="resolution"]').text()).toContain(
      'fingerprint',
    );
    expect(wrapper.get('[data-test="resolution"]').text()).toContain('C-1001');
  });

  it('判定被阻断（冲突/过期）时不展示结果，只提示错误码', async () => {
    vi.mocked(resolveObjectKey).mockRejectedValueOnce(new Error('conflict'));
    const wrapper = await render();

    await wrapper.get('[data-test="resolve"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-test="resolution"]').exists()).toBe(false);
    expect(showRequestError).toHaveBeenCalledWith(
      expect.any(Error),
      '判定被阻断：请查看错误码（冲突/过期/未发布）',
    );
  });

  it('反查未登记是有效结论（mapped=false），不是错误', async () => {
    vi.mocked(resolveSourceKey).mockResolvedValue({
      applicationId: 7,
      asOf: '2026-09-01T00:00:00',
      entityType: 'customer',
      mapped: false,
      reason: 'NOT_REGISTERED',
      sourceKey: 'C-1003',
    });
    const wrapper = await render();

    await wrapper.get('[data-test="reverse"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-test="reverse-result"]').text()).toContain(
      '未登记（未映射即不关联）',
    );
    expect(showRequestError).not.toHaveBeenCalled();
  });

  it('目录被拒绝时展示与"未登记"同形的说明', async () => {
    vi.mocked(getMasterObjectCatalog).mockResolvedValue({
      asOf: '2026-09-01T00:00:00',
      catalogFingerprint: 'catalog-fingerprint',
      denied: true,
      entries: [],
      masterObjectId: 5,
      modelCatalog: '[]',
      objectCode: 'md_cloud_qi',
      objectName: '云启科技（统一客户）',
      objectType: 'CUSTOMER',
      revisionFingerprint: 'fingerprint',
      revisionNo: 1,
    });
    const wrapper = await render();

    await wrapper.get('[data-test="catalog"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-test="catalog-denied"]').text()).toContain(
      '没有任何可访问系统',
    );
    expect(wrapper.get('[data-test="catalog-fingerprint"]').text()).toContain(
      'catalog-fingerprint',
    );
  });

  it('目录条目照实展示无权/冲突问题标注', async () => {
    vi.mocked(getMasterObjectCatalog).mockResolvedValue({
      asOf: '2026-09-01T00:00:00',
      catalogFingerprint: 'catalog-fingerprint',
      denied: false,
      entries: [
        {
          appCode: 'it-md-crm',
          applicationId: 7,
          entityType: 'customer',
          inForce: true,
          matchMethod: 'MANUAL',
          problem: 'CONFLICT',
          sourceKey: 'C-1001',
          sourceName: '杭州云启科技有限公司',
          systemName: 'CRM 系统',
          usable: false,
          validFrom: '2026-01-01T00:00:00',
        },
      ],
      masterObjectId: 5,
      modelCatalog: '{}',
      objectCode: 'md_cloud_qi',
      objectName: '云启科技（统一客户）',
      objectType: 'CUSTOMER',
      revisionFingerprint: 'fingerprint',
      revisionNo: 1,
    });
    const wrapper = await render();

    await wrapper.get('[data-test="catalog"]').trigger('click');
    await flushPromises();

    const text = wrapper.get('[data-test="catalog-entries"]').text();
    expect(text).toContain('C-1001');
    expect(text).toContain('CONFLICT');
    // 模型可见目录原文不直接渲染（键值不进提示词，页面只展示可见条目）
    expect(text).not.toContain('modelCatalog');
  });
});
