import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createConnector,
  executeConnectorOperation,
  importConnectorOperations,
  listConnectorOperations,
  publishConnectorOperation,
  updateConnector,
} from '#/api/ai/data';
import { showSuccessMessage } from '#/utils/feedback';

import Form from './form.vue';
import Operations from './operations.vue';

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
  createConnector: vi.fn(),
  executeConnectorOperation: vi.fn(),
  importConnectorOperations: vi.fn(),
  listConnectorOperations: vi.fn(),
  publishConnectorOperation: vi.fn(),
  updateConnector: vi.fn(),
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

describe('ai connector modules', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfigs.length = 0;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(undefined);
  });

  it('新增连接器：把结构化字段拼成声明式配置并提交秘密', async () => {
    state.formApi.getValues.mockResolvedValue({
      baseUrl: 'https://crm.example.com',
      code: 'crm-http',
      configKind: 'HTTP',
      connectorType: 'HTTP',
      credential: 'token-1',
      method: 'GET',
      name: 'CRM 接口',
    });
    vi.mocked(createConnector).mockResolvedValue(1);
    mount(Form);
    await flushPromises();

    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await modal.onConfirm?.();

    expect(createConnector).toHaveBeenCalledWith({
      code: 'crm-http',
      configJson: JSON.stringify({
        authType: 'BEARER',
        baseUrl: 'https://crm.example.com',
        method: 'GET',
      }),
      connectorType: 'HTTP',
      name: 'CRM 接口',
      credential: 'token-1',
    });
    expect(state.modalApi.close).toHaveBeenCalled();
  });

  it('编辑连接器：回填声明式配置并带乐观锁版本', async () => {
    state.modalApi.getData.mockReturnValue({
      code: 'crm-readonly',
      configJson: JSON.stringify({
        allowedObjects: ['crm.orders'],
        database: 'crm',
        host: 'db.internal',
        port: 3306,
        username: 'readonly',
      }),
      connectorType: 'MYSQL',
      id: 71,
      name: 'CRM 只读库',
      version: 3,
    });
    state.formApi.getValues.mockResolvedValue({
      code: 'crm-readonly',
      configKind: 'MYSQL',
      connectorType: 'MYSQL',
      credential: '',
      database: 'crm',
      host: 'db.internal',
      name: 'CRM 只读库',
      port: 3306,
      sslMode: 'REQUIRED',
      username: 'readonly',
      allowedObjects: 'crm.orders',
    });
    vi.mocked(updateConnector).mockResolvedValue(true);
    mount(Form);
    await flushPromises();

    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    expect(state.formApi.setValues).toHaveBeenCalled();
    await modal.onConfirm?.();

    expect(updateConnector).toHaveBeenCalledWith(
      expect.objectContaining({ id: 71, version: 3 }),
    );
    // 未填秘密时不提交 credential 字段（保留原秘密）
    expect(
      (
        vi.mocked(updateConnector).mock.calls[0]?.[0] as unknown as Record<
          string,
          unknown
        >
      ).credential,
    ).toBeUndefined();
  });

  it('表单校验失败时不提交', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mount(Form);
    await flushPromises();
    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await modal.onConfirm?.();
    expect(createConnector).not.toHaveBeenCalled();
  });

  it('接口管理：导入草稿、显式发布、试跑只带声明参数', async () => {
    state.modalApi.getData.mockReturnValue({ id: 71, name: 'CRM 接口' });
    vi.mocked(listConnectorOperations).mockResolvedValue([
      {
        connectorId: 71,
        httpMethod: 'GET',
        id: 81,
        operationKey: 'getOrders',
        parameterJson: '{"region":{"required":true,"type":"string"}}',
        pathTemplate: '/orders',
        status: 'DRAFT',
        version: 0,
      },
    ]);
    vi.mocked(importConnectorOperations).mockResolvedValue({
      operationKeys: ['getOrders'],
      skipped: ['外部 $ref 已跳过'],
    });
    vi.mocked(publishConnectorOperation).mockResolvedValue(true);
    vi.mocked(executeConnectorOperation).mockResolvedValue({
      detailCode: 'TARGET_NOT_ALLOWED',
      itemCount: 0,
      status: 'FAILED',
      stoppedReason: 'upstream-failed',
    });

    const wrapper = mount(Operations);
    await flushPromises();
    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await flushPromises();

    await requireElement(wrapper.findAll('textarea'), 0).setValue(
      '{"openapi":"3.0.0"}',
    );
    const buttons = wrapper.findAll('button');
    // 导入按钮
    await requireElement(buttons, 0).trigger('click');
    await flushPromises();
    expect(importConnectorOperations).toHaveBeenCalled();

    // 发布 → 草稿不可执行，必须显式发布
    const publishButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '发布');
    await requireButton(publishButton).trigger('click');
    await flushPromises();
    expect(publishConnectorOperation).toHaveBeenCalledWith(81, 0);

    // 试跑：payload 只有 connectorId/operationKey/arguments（没有 URL/请求头/SQL 面）
    vi.mocked(listConnectorOperations).mockResolvedValue([
      {
        connectorId: 71,
        httpMethod: 'GET',
        id: 81,
        operationKey: 'getOrders',
        parameterJson: '{"region":{"required":true,"type":"string"}}',
        pathTemplate: '/orders',
        status: 'PUBLISHED',
        version: 1,
      },
    ]);
    await modal.onOpenChange(true);
    await flushPromises();
    const runButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '试跑');
    await requireButton(runButton).trigger('click');
    await flushPromises();
    expect(executeConnectorOperation).toHaveBeenCalledWith(71, 'getOrders', {});
    expect(wrapper.text()).toContain('FAILED');
    expect(wrapper.text()).toContain('TARGET_NOT_ALLOWED');
  });

  it('接口管理：参数不是 JSON 对象时给出明确错误', async () => {
    state.modalApi.getData.mockReturnValue({ id: 71, name: 'CRM 接口' });
    vi.mocked(listConnectorOperations).mockResolvedValue([
      {
        connectorId: 71,
        httpMethod: 'GET',
        id: 81,
        operationKey: 'getOrders',
        pathTemplate: '/orders',
        status: 'PUBLISHED',
        version: 1,
      },
    ]);
    const wrapper = mount(Operations);
    await flushPromises();
    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await flushPromises();

    const textarea = requireElement(wrapper.findAll('textarea'), 1);
    await textarea.setValue('not-json');
    const runButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '试跑');
    await requireButton(runButton).trigger('click');
    await flushPromises();

    expect(executeConnectorOperation).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('参数必须是 JSON 对象');
    expect(showSuccessMessage).not.toHaveBeenCalled();
  });
});
