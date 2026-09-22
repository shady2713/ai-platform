import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createDataset,
  createDatasetVersion,
  getDatasetVersionPage,
  publishDatasetVersion,
  updateDataset,
  verifyDatasetVersion,
} from '#/api/ai/data';
import { showSuccessMessage } from '#/utils/feedback';

import Form from './form.vue';
import Versions from './versions.vue';

interface ModalConfig {
  onConfirm?: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void> | void;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(),
    resetForm: vi.fn(() => Promise.resolve()),
    setValues: vi.fn(() => Promise.resolve()),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  modalApi: { close: vi.fn(), getData: vi.fn(), setState: vi.fn() },
  modalConfigs: [] as ModalConfig[],
}));

vi.mock('@vben/common-ui', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/common-ui')>('@vben/common-ui');
  const { defineComponent, h } = await import('vue');
  return {
    z: actual.z,
    useVbenModal: vi.fn((config: ModalConfig) => {
      state.modalConfigs.push(config);
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
    useVbenForm: vi.fn(() => [
      defineComponent({
        name: 'FormStub',
        setup() {
          return () => h('div', { 'data-test': 'form' });
        },
      }),
      state.formApi,
    ]),
  };
});

vi.mock('#/api/ai/data', () => ({
  createDataset: vi.fn(),
  createDatasetVersion: vi.fn(),
  getDatasetVersionPage: vi.fn(),
  publishDatasetVersion: vi.fn(),
  updateDataset: vi.fn(),
  verifyDatasetVersion: vi.fn(),
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));
vi.mock('@vben/utils', () => ({ cloneDeep: (value: unknown) => value }));

/** 测试助手：最近一次注册的弹窗配置必须存在。 */
function requireModalConfig(): ModalConfig {
  const config = state.modalConfigs.at(-1);
  if (!config) {
    throw new Error('弹窗配置未注册');
  }
  return config;
}

/** 测试助手：目标必须存在（找不到直接失败，避免非空断言）。 */
function requireButton<T>(item: T | undefined): T {
  if (item === undefined) {
    throw new Error('按钮不存在');
  }
  return item;
}

/** 测试助手：找不到元素直接失败，避免使用非空断言。 */
function requireElement<T>(items: T[], index: number): T {
  const item = items[index];
  if (item === undefined) {
    throw new Error(`缺少第 ${index} 个元素`);
  }
  return item;
}

describe('ai dataset modules', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfigs.length = 0;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(undefined);
    vi.mocked(getDatasetVersionPage).mockResolvedValue({ list: [], total: 0 });
  });

  it('新增数据集：来源对象与连接器一起提交', async () => {
    state.formApi.getValues.mockResolvedValue({
      code: 'crm-orders',
      connectorId: 71,
      description: '订单',
      name: 'CRM 订单',
      sourceObject: 'crm.orders',
    });
    vi.mocked(createDataset).mockResolvedValue(81);
    mount(Form);
    await flushPromises();

    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await modal.onConfirm?.();

    expect(createDataset).toHaveBeenCalledWith({
      code: 'crm-orders',
      connectorId: 71,
      description: '订单',
      name: 'CRM 订单',
      sourceObject: 'crm.orders',
    });
    expect(state.modalApi.close).toHaveBeenCalled();
  });

  it('编辑数据集：带乐观锁版本', async () => {
    state.modalApi.getData.mockReturnValue({
      code: 'crm-orders',
      connectorId: 71,
      id: 81,
      name: 'CRM 订单',
      sourceObject: 'crm.orders',
      version: 3,
    });
    state.formApi.getValues.mockResolvedValue({
      code: 'crm-orders',
      connectorId: 71,
      name: 'CRM 订单',
      sourceObject: 'crm.orders',
    });
    vi.mocked(updateDataset).mockResolvedValue(true);
    mount(Form);
    await flushPromises();

    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await modal.onConfirm?.();
    expect(updateDataset).toHaveBeenCalledWith(
      expect.objectContaining({ id: 81, version: 3 }),
    );
  });

  it('版本：创建草稿 → 验证展示漂移原因 → 发布失败展示原因', async () => {
    state.modalApi.getData.mockReturnValue({ id: 81, name: 'CRM 订单' });
    vi.mocked(createDatasetVersion).mockResolvedValue(91);
    vi.mocked(verifyDatasetVersion).mockResolvedValue({
      addedColumns: [],
      missingColumns: ['status'],
      publishable: false,
      schemaHash: 'a',
      sourceSchemaHash: 'b',
      status: 'DRAFT',
      typeChangedColumns: [],
      verificationStatus: 'DRIFTED',
      versionId: 91,
      versionNo: 1,
    });
    vi.mocked(getDatasetVersionPage).mockResolvedValue({
      list: [
        {
          datasetId: 81,
          definitionJson: '{}',
          id: 91,
          schemaHash: 'a',
          status: 'DRAFT',
          verificationStatus: 'DRIFTED',
          version: 2,
          versionNo: 1,
        },
      ],
      total: 1,
    });
    vi.mocked(publishDatasetVersion).mockRejectedValue(
      new Error('版本尚未验证，不能发布'),
    );

    const wrapper = mount(Versions);
    await flushPromises();
    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await flushPromises();

    // 创建草稿
    await requireElement(wrapper.findAll('textarea'), 0).setValue(
      '{"grain":"一行一单"}',
    );
    const createButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '创建草稿');
    await requireButton(createButton).trigger('click');
    await flushPromises();
    expect(createDatasetVersion).toHaveBeenCalledWith(
      81,
      '{"grain":"一行一单"}',
    );

    // 验证：漂移原因必须展示
    const verifyButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '验证');
    await requireButton(verifyButton).trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('上游缺少列：status');

    // 发布失败：原因原样展示（清掉创建步骤的成功提示，只断言发布不报成功）
    vi.mocked(showSuccessMessage).mockClear();
    const publishButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '发布');
    await requireButton(publishButton).trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('版本尚未验证，不能发布');
    expect(showSuccessMessage).not.toHaveBeenCalled();
  });

  it('版本：语义定义为空时拒绝创建并提示', async () => {
    state.modalApi.getData.mockReturnValue({ id: 81, name: 'CRM 订单' });
    const wrapper = mount(Versions);
    await flushPromises();
    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await flushPromises();

    const createButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '创建草稿');
    await requireButton(createButton).trigger('click');
    await flushPromises();

    expect(createDatasetVersion).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请填写语义定义');
  });
});
