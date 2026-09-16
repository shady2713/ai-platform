import type { MessageBoxData } from 'element-plus';

import type { InfraConfigApi } from '#/api/infra/config';

import { flushPromises, mount } from '@vue/test-utils';

import { downloadFileFromBlobPart } from '@vben/utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteConfig,
  deleteConfigList,
  exportConfig,
  getConfigPage,
} from '#/api/infra/config';
import { showConfirmDialog, showSuccessMessage } from '#/utils/feedback';

import ConfigIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

interface GridEvents {
  checkboxChange?: (params: { records: unknown[] }) => void;
}

const state = vi.hoisted(() => ({
  closeLoading: vi.fn(),
  gridApi: {
    formApi: { getValues: vi.fn() },
    query: vi.fn(),
  },
  gridConfig: undefined as GridConfig | undefined,
  gridEvents: undefined as GridEvents | undefined,
  modalApi: {
    open: vi.fn(),
    setData: vi.fn(() => state.modalApi),
  },
  row: {} as InfraConfigApi.Config,
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

vi.mock('@vben/utils', () => ({
  downloadFileFromBlobPart: vi.fn(),
  isEmpty: (value: unknown) =>
    Array.isArray(value) ? value.length === 0 : !value,
}));

vi.mock('#/adapter/vxe-table', async () => {
  const { defineComponent, h } = await import('vue');
  const renderAction = (action: Record<string, unknown>) => {
    const popConfirm = action.popConfirm as { confirm?: () => unknown };
    const handler = popConfirm?.confirm ?? (action.onClick as () => unknown);
    return h(
      'button',
      { 'data-action': String(action.label), onClick: handler },
      String(action.label),
    );
  };
  return {
    ACTION_ICON: {
      ADD: 'lucide:plus',
      DELETE: 'lucide:trash-2',
      DOWNLOAD: 'lucide:download',
      EDIT: 'lucide:edit',
    },
    TableAction: defineComponent({
      name: 'TableAction',
      props: {
        actions: { type: Array, default: () => [] },
        dropDownActions: { type: Array, default: () => [] },
      },
      setup(props) {
        return () =>
          h('div', [
            ...(props.actions as Record<string, unknown>[]).map((action) =>
              renderAction(action),
            ),
            ...(props.dropDownActions as Record<string, unknown>[]).map(
              (action) => renderAction(action),
            ),
          ]);
      },
    }),
    useVbenVxeGrid: vi.fn((gridOptions: unknown) => {
      const options = gridOptions as GridConfig & {
        gridEvents?: GridEvents;
      };
      state.gridConfig = gridOptions as GridConfig;
      state.gridEvents = options.gridEvents;
      return [
        defineComponent({
          name: 'GridStub',
          setup(_props, { slots }) {
            return () =>
              h('div', { 'data-test': 'grid' }, [
                slots.default?.(),
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

vi.mock('element-plus', () => ({
  ElLoading: { service: vi.fn(() => ({ close: state.closeLoading })) },
}));

vi.mock('#/api/infra/config', () => ({
  deleteConfig: vi.fn(),
  deleteConfigList: vi.fn(),
  exportConfig: vi.fn(),
  getConfigPage: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showConfirmDialog: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('./data', () => ({
  useGridColumns: vi.fn(() => []),
  useGridFormSchema: vi.fn(() => []),
}));

vi.mock('./modules/form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'ConfigFormStub', template: '<div />' }),
  };
});

function row(
  overrides: Partial<InfraConfigApi.Config> = {},
): InfraConfigApi.Config {
  return {
    category: 'basic',
    id: 1,
    key: 'system.name',
    name: '系统名称',
    remark: '',
    type: 1,
    value: 'admin',
    visible: true,
    ...overrides,
  };
}

function queryConfig() {
  return state.gridConfig?.gridOptions.proxyConfig.ajax.query;
}

function mountPage() {
  return mount(ConfigIndex, { attachTo: document.body });
}

function actionButton(wrapper: ReturnType<typeof mountPage>, label: string) {
  const button = wrapper
    .findAll('button[data-action]')
    .find((candidate) => candidate.attributes('data-action') === label);
  if (!button) {
    throw new Error(`未找到操作按钮：${label}`);
  }
  return button;
}

describe('infra config page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = row();
    state.gridConfig = undefined;
    state.gridEvents = undefined;
    vi.mocked(getConfigPage).mockResolvedValue({
      list: [row()],
      total: 1,
    });
    vi.mocked(showConfirmDialog).mockResolvedValue({
      action: 'confirm',
      value: '',
    } as unknown as MessageBoxData);
    state.gridApi.formApi.getValues.mockResolvedValue({ name: '过滤参数' });
    vi.mocked(exportConfig).mockResolvedValue(new Blob(['xls']));
  });

  it('queries the config page with paging and search values', async () => {
    mountPage();

    expect(queryConfig()).toBeTypeOf('function');
    await queryConfig()?.(
      { page: { currentPage: 2, pageSize: 20 } },
      { name: 'x' },
    );

    expect(getConfigPage).toHaveBeenCalledWith({
      name: 'x',
      pageNo: 2,
      pageSize: 20,
    });
  });

  it('opens the create modal from the toolbar', async () => {
    const wrapper = mountPage();
    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');

    expect(state.modalApi.setData).toHaveBeenCalledWith(null);
    expect(state.modalApi.open).toHaveBeenCalledOnce();
  });

  it('edits the selected row and deletes it after confirmation', async () => {
    const wrapper = mountPage();
    await actionButton(wrapper, 'common.edit').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();

    expect(deleteConfig).toHaveBeenCalledWith(1);
    expect(showSuccessMessage).toHaveBeenCalled();
    expect(state.gridApi.query).toHaveBeenCalled();
    expect(state.closeLoading).toHaveBeenCalledOnce();
  });

  it('batch deletes the checked rows', async () => {
    const wrapper = mountPage();
    state.gridEvents?.checkboxChange?.({
      records: [row({ id: 1 }), row({ id: 2 })],
    });

    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();

    expect(showConfirmDialog).toHaveBeenCalledOnce();
    expect(deleteConfigList).toHaveBeenCalledWith([1, 2]);
    expect(showSuccessMessage).toHaveBeenCalled();
  });

  it('exports the current search values as an xls download', async () => {
    const wrapper = mountPage();
    await actionButton(wrapper, 'ui.actionTitle.export').trigger('click');
    await flushPromises();

    expect(exportConfig).toHaveBeenCalledWith({ name: '过滤参数' });
    expect(downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: '参数配置.xls',
      source: expect.any(Blob),
    });
  });
});
