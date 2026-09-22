import type { AiDatasetApi } from '#/api/ai/data';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteDataset,
  getDatasetPage,
  updateDatasetStatus,
} from '#/api/ai/data';
import { showSuccessMessage } from '#/utils/feedback';

import DatasetIndex from './index.vue';

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
  deleteDataset: vi.fn(),
  getDatasetPage: vi.fn(),
  updateDatasetStatus: vi.fn(),
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));
vi.mock('./modules/form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'FormStub', template: '<div />' }),
  };
});
vi.mock('./modules/versions.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'VersionsStub', template: '<div />' }),
  };
});

function dataset(
  overrides: Partial<AiDatasetApi.Dataset> = {},
): AiDatasetApi.Dataset {
  return {
    code: 'crm-orders',
    connectorId: 71,
    id: 81,
    name: 'CRM 订单',
    sourceObject: 'crm.orders',
    status: 'ENABLED',
    version: 3,
    ...overrides,
  };
}

function mountPage() {
  return mount(DatasetIndex, { attachTo: document.body });
}

function actionButton(wrapper: ReturnType<typeof mountPage>, label: string) {
  return wrapper.element.querySelector(
    `button[data-action="${label}"]`,
  ) as HTMLButtonElement | null;
}

describe('ai dataset page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = dataset();
    state.modalApi.setData.mockReturnValue(state.modalApi);
  });

  it('版本/删除/新增入口受权限码控制', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(actionButton(wrapper, '语义版本')?.dataset.auth).toBe(
      'ai:dataset:query',
    );
    expect(actionButton(wrapper, 'common.delete')?.dataset.auth).toBe(
      'ai:dataset:delete',
    );
    expect(actionButton(wrapper, 'ui.actionTitle.create')?.dataset.auth).toBe(
      'ai:dataset:create',
    );
  });

  it('启停与删除带乐观锁版本', async () => {
    vi.mocked(updateDatasetStatus).mockResolvedValue(true);
    vi.mocked(deleteDataset).mockResolvedValue(true);
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '停用')?.dispatchEvent(new Event('click'));
    await flushPromises();
    expect(updateDatasetStatus).toHaveBeenCalledWith(81, 3, false);

    actionButton(wrapper, 'common.delete')?.dispatchEvent(new Event('click'));
    await flushPromises();
    expect(deleteDataset).toHaveBeenCalledWith(81, 3);
    expect(showSuccessMessage).toHaveBeenCalled();
  });

  it('分页查询带过滤条件，空结果不报错', async () => {
    vi.mocked(getDatasetPage).mockResolvedValue({ list: [], total: 0 });
    const wrapper = mountPage();
    await flushPromises();

    const query = (state.gridConfig as GridConfig).gridOptions.proxyConfig.ajax
      .query;
    await query(
      { page: { currentPage: 1, pageSize: 20 } },
      { code: 'crm-orders' },
    );
    expect(getDatasetPage).toHaveBeenCalledWith({
      code: 'crm-orders',
      pageNo: 1,
      pageSize: 20,
    });
    expect(wrapper.text()).not.toContain('undefined');
  });

  it('打开语义版本弹窗时传入当前行', async () => {
    const wrapper = mountPage();
    await flushPromises();
    actionButton(wrapper, '语义版本')?.dispatchEvent(new Event('click'));
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);
    expect(state.modalApi.open).toHaveBeenCalled();
  });
});
