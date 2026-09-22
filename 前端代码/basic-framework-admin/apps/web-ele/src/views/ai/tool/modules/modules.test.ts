import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createTool,
  createToolVersion,
  getToolVersionPage,
  publishToolVersion,
  updateTool,
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
  createTool: vi.fn(),
  createToolVersion: vi.fn(),
  getToolVersionPage: vi.fn(),
  publishToolVersion: vi.fn(),
  updateTool: vi.fn(),
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

describe('ai tool modules', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfigs.length = 0;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(undefined);
    vi.mocked(getToolVersionPage).mockResolvedValue({ list: [], total: 0 });
  });

  it('新增工具：绑定连接器', async () => {
    state.formApi.getValues.mockResolvedValue({
      code: 'query-orders',
      connectorId: 71,
      description: '查询订单',
      name: '查询订单',
    });
    vi.mocked(createTool).mockResolvedValue(91);
    mount(Form);
    await flushPromises();

    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await modal.onConfirm?.();
    expect(createTool).toHaveBeenCalledWith({
      code: 'query-orders',
      connectorId: 71,
      description: '查询订单',
      name: '查询订单',
    });
  });

  it('编辑工具：带乐观锁版本', async () => {
    state.modalApi.getData.mockReturnValue({
      code: 'query-orders',
      connectorId: 71,
      id: 91,
      name: '查询订单',
      version: 4,
    });
    state.formApi.getValues.mockResolvedValue({
      code: 'query-orders',
      connectorId: 71,
      name: '查询订单',
    });
    vi.mocked(updateTool).mockResolvedValue(true);
    mount(Form);
    await flushPromises();
    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await modal.onConfirm?.();
    expect(updateTool).toHaveBeenCalledWith(
      expect.objectContaining({ id: 91, version: 4 }),
    );
  });

  it('版本：默认 DENY 创建草稿；写工具发布失败展示后端原因', async () => {
    state.modalApi.getData.mockReturnValue({ id: 91, name: '查询订单' });
    vi.mocked(createToolVersion).mockResolvedValue(101);
    vi.mocked(getToolVersionPage).mockResolvedValue({
      list: [
        {
          id: 101,
          inputSchemaJson: '{}',
          outputSchemaJson: '{}',
          policy: 'DENY',
          schemaHash: 'a',
          sourceKind: 'HTTP_OPERATION',
          sourceRef: 'getOrders',
          status: 'DRAFT',
          toolId: 91,
          toolType: 'WRITE',
          version: 0,
          versionNo: 1,
        },
      ],
      total: 1,
    });
    vi.mocked(publishToolVersion).mockRejectedValue(
      new Error('首期只支持读工具'),
    );

    const wrapper = mount(Versions);
    await flushPromises();
    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await flushPromises();

    // 默认政策是 DENY（界面显示默认值）
    expect(wrapper.text()).toContain('DENY');

    // 创建草稿：来源与 schema 一并提交
    await requireElement(wrapper.findAll('input'), 0).setValue('getOrders');
    const createButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '创建版本草稿');
    await requireButton(createButton).trigger('click');
    await flushPromises();
    expect(createToolVersion).toHaveBeenCalledWith(
      expect.objectContaining({
        policy: 'DENY',
        sourceRef: 'getOrders',
        toolId: 91,
        toolType: 'READ',
      }),
    );

    // 发布失败：原因原样展示
    vi.mocked(showSuccessMessage).mockClear();
    const publishButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '发布');
    await requireButton(publishButton).trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('首期只支持读工具');
    expect(showSuccessMessage).not.toHaveBeenCalled();
  });

  it('版本：来源为空时后端拒绝（界面展示原因，不静默成功）', async () => {
    state.modalApi.getData.mockReturnValue({ id: 91, name: '查询订单' });
    vi.mocked(createToolVersion).mockRejectedValue(
      new Error('请填写已发布的 operationKey'),
    );
    const wrapper = mount(Versions);
    await flushPromises();
    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await flushPromises();

    const createButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '创建版本草稿');
    await requireButton(createButton).trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('请填写已发布的 operationKey');
    expect(showSuccessMessage).not.toHaveBeenCalled();
  });
});
