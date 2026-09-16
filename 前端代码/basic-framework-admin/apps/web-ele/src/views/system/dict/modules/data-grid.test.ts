import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteDictData,
  deleteDictDataList,
  exportDictData,
  getDictDataPage,
} from '#/api/system/dict/data';

import DictDataGrid from './data-grid.vue';

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
  downloadFileFromBlobPart: vi.fn(),
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
  row: {
    colorType: 'primary',
    createTime: new Date('2026-09-01T00:00:00Z'),
    cssClass: '',
    dictType: 'sex',
    id: 1,
    label: '男',
    remark: '',
    status: 0,
    value: '1',
  },
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
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
  downloadFileFromBlobPart: state.downloadFileFromBlobPart,
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
              h('div', [
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
  ElLoading: { service: vi.fn(() => ({ close: vi.fn() })) },
}));

vi.mock('#/api/system/dict/data', () => ({
  deleteDictData: vi.fn(),
  deleteDictDataList: vi.fn(),
  exportDictData: vi.fn(),
  getDictDataPage: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showConfirmDialog: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('../data', () => ({
  useDataGridColumns: vi.fn(() => []),
  useDataGridFormSchema: vi.fn(() => []),
}));

vi.mock('./data-form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'DataFormStub', template: '<div />' }),
  };
});

function mountGrid(props: { dictType?: string } = {}) {
  return mount(DictDataGrid, { props, attachTo: document.body });
}

function actionButton(wrapper: ReturnType<typeof mountGrid>, label: string) {
  const button = wrapper
    .findAll('button[data-action]')
    .find((candidate) => candidate.attributes('data-action') === label);
  if (!button) {
    throw new Error(`未找到操作按钮：${label}`);
  }
  return button;
}

describe('dict data grid', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.gridConfig = undefined;
    state.gridEvents = undefined;
    vi.mocked(getDictDataPage).mockResolvedValue({
      list: [state.row],
      total: 1,
    });
  });

  it('queries dict data scoped to the given dict type', async () => {
    mountGrid({ dictType: 'sex' });
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 1, pageSize: 10 } }, { name: 'x' });

    expect(getDictDataPage).toHaveBeenCalledWith({
      dictType: 'sex',
      name: 'x',
      pageNo: 1,
      pageSize: 10,
    });
  });

  it('creates data with the current dict type preset', async () => {
    const wrapper = mountGrid({ dictType: 'sex' });

    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');

    expect(state.modalApi.setData).toHaveBeenCalledWith({ dictType: 'sex' });
    expect(state.modalApi.open).toHaveBeenCalledOnce();
  });

  it('refreshes the grid when the dict type changes', async () => {
    const wrapper = mountGrid();

    await wrapper.setProps({ dictType: 'sex' });
    expect(state.gridApi.query).toHaveBeenCalledOnce();

    await wrapper.setProps({ dictType: undefined });
    expect(state.gridApi.query).toHaveBeenCalledOnce();
  });

  it('exports and deletes dict data including batch rows', async () => {
    const wrapper = mountGrid({ dictType: 'sex' });
    state.gridApi.formApi.getValues.mockResolvedValue({ label: 'x' });
    vi.mocked(exportDictData).mockResolvedValue(new Blob(['xls']));

    await actionButton(wrapper, 'ui.actionTitle.export').trigger('click');
    await flushPromises();
    expect(exportDictData).toHaveBeenCalledWith({ label: 'x' });
    expect(state.downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: '字典数据.xls',
      source: expect.any(Blob),
    });

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();
    expect(deleteDictData).toHaveBeenCalledWith(1);

    state.gridEvents?.checkboxChange?.({
      records: [
        { id: 1, label: '男' },
        { id: 2, label: '女' },
      ],
    });
    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();
    expect(deleteDictDataList).toHaveBeenCalledWith([1, 2]);
  });
});
