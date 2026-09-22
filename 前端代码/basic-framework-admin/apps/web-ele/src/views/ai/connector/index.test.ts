import type { AiConnectorApi } from '#/api/ai/data';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteConnector,
  getConnectorPage,
  probeConnector,
  updateConnectorStatus,
} from '#/api/ai/data';
import { showSuccessMessage } from '#/utils/feedback';

import ConnectorIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

const state = vi.hoisted(() => ({
  gridApi: { query: vi.fn() },
  gridConfig: undefined as unknown,
  modalApi: { open: vi.fn(), setData: vi.fn(() => state.modalApi) },
  row: {} as unknown,
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', slots.default?.());
      },
    }),
    useVbenModal: vi.fn(() => [
      defineComponent({
        name: 'ModalStub',
        setup(_props, { slots }) {
          return () => h('section', slots.default?.());
        },
      }),
      state.modalApi,
    ]),
  };
});

vi.mock('#/adapter/vxe-table', async () => {
  const { defineComponent, h } = await import('vue');
  const renderAction = (action: Record<string, unknown>) => {
    const popConfirm = action.popConfirm as { confirm?: () => unknown };
    const handler = popConfirm?.confirm ?? (action.onClick as () => unknown);
    return h(
      'button',
      {
        'data-action': String(action.label),
        'data-auth': (action.auth as string[] | undefined)?.join(',') ?? '',
        onClick: handler,
      },
      String(action.label),
    );
  };
  return {
    ACTION_ICON: { ADD: 'plus', DELETE: 'trash', EDIT: 'edit' },
    TableAction: defineComponent({
      name: 'TableAction',
      props: { actions: { type: Array, default: () => [] } },
      setup(props) {
        return () =>
          h(
            'div',
            (props.actions as Record<string, unknown>[]).map((action) =>
              renderAction(action),
            ),
          );
      },
    }),
    useVbenVxeGrid: vi.fn((gridOptions: unknown) => {
      state.gridConfig = gridOptions;
      return [
        defineComponent({
          name: 'GridStub',
          setup(_props, { slots }) {
            return () =>
              h('div', { 'data-test': 'grid' }, [
                slots['toolbar-tools']?.(),
                slots.actions?.({ row: state.row }),
              ]);
          },
        }),
        state.gridApi,
      ];
    }),
  };
});

vi.mock('#/api/ai/data', () => ({
  deleteConnector: vi.fn(),
  getConnectorPage: vi.fn(),
  probeConnector: vi.fn(),
  updateConnectorStatus: vi.fn(),
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));
vi.mock('./modules/form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'FormStub', template: '<div />' }),
  };
});
vi.mock('./modules/operations.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'OperationsStub', template: '<div />' }),
  };
});

function connector(
  overrides: Partial<AiConnectorApi.Connector> = {},
): AiConnectorApi.Connector {
  return {
    code: 'crm-readonly',
    configJson: '{"host":"db.internal"}',
    connectorType: 'MYSQL',
    credentialConfigured: true,
    id: 71,
    name: 'CRM 只读库',
    status: 'ENABLED',
    version: 2,
    ...overrides,
  };
}

function mountPage() {
  return mount(ConnectorIndex, { attachTo: document.body });
}

function actionButton(wrapper: ReturnType<typeof mountPage>, label: string) {
  return wrapper.element.querySelector(
    `button[data-action="${label}"]`,
  ) as HTMLButtonElement | null;
}

describe('ai connector page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = connector();
    state.modalApi.setData.mockReturnValue(state.modalApi);
  });

  it('探测/接口/删除入口受权限码控制', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(actionButton(wrapper, '连接测试')?.dataset.auth).toBe(
      'ai:connector:probe',
    );
    expect(actionButton(wrapper, '接口管理')?.dataset.auth).toBe(
      'ai:connector:import',
    );
    expect(actionButton(wrapper, 'common.delete')?.dataset.auth).toBe(
      'ai:connector:delete',
    );
    expect(actionButton(wrapper, 'ui.actionTitle.create')?.dataset.auth).toBe(
      'ai:connector:create',
    );
  });

  it('连接测试失败时展示稳定原因码（不翻译成模糊提示）', async () => {
    vi.mocked(probeConnector).mockResolvedValue({
      connectorId: 71,
      detailCode: 'TARGET_NOT_ALLOWED',
      probeKind: 'HTTP',
      status: 'FAILED',
    });
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '连接测试')?.dispatchEvent(new Event('click'));
    await flushPromises();

    expect(probeConnector).toHaveBeenCalledWith(71);
    expect(wrapper.text()).toContain('TARGET_NOT_ALLOWED');
  });

  it('连接测试成功时展示耗时', async () => {
    vi.mocked(probeConnector).mockResolvedValue({
      connectorId: 71,
      latencyMs: 12,
      probeKind: 'MYSQL_CONNECTIVITY',
      status: 'SUPPORTED',
    });
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '连接测试')?.dispatchEvent(new Event('click'));
    await flushPromises();

    expect(wrapper.text()).toContain('连接可用');
  });

  it('启停与删除都带乐观锁版本', async () => {
    vi.mocked(updateConnectorStatus).mockResolvedValue(true);
    vi.mocked(deleteConnector).mockResolvedValue(true);
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '停用')?.dispatchEvent(new Event('click'));
    await flushPromises();
    expect(updateConnectorStatus).toHaveBeenCalledWith(71, 2, false);

    actionButton(wrapper, 'common.delete')?.dispatchEvent(new Event('click'));
    await flushPromises();
    expect(deleteConnector).toHaveBeenCalledWith(71, 2);
    expect(showSuccessMessage).toHaveBeenCalled();
  });

  it('分页查询带上页码与表单过滤条件；空结果不报错', async () => {
    vi.mocked(getConnectorPage).mockResolvedValue({ list: [], total: 0 });
    const wrapper = mountPage();
    await flushPromises();

    const query = (state.gridConfig as GridConfig).gridOptions.proxyConfig.ajax
      .query;
    await query(
      { page: { currentPage: 2, pageSize: 10 } },
      { status: 'ENABLED' },
    );

    expect(getConnectorPage).toHaveBeenCalledWith({
      pageNo: 2,
      pageSize: 10,
      status: 'ENABLED',
    });
    expect(wrapper.element.querySelector('table')).toBeNull();
  });

  it('打开接口管理与编辑弹窗时传入当前行', async () => {
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '接口管理')?.dispatchEvent(new Event('click'));
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);

    actionButton(wrapper, 'common.edit')?.dispatchEvent(new Event('click'));
    expect(state.modalApi.open).toHaveBeenCalledTimes(2);
  });
});
