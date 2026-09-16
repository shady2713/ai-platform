import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteDictType,
  deleteDictTypeList,
  exportDictType,
  getDictTypePage,
} from '#/api/system/dict/type';

import DictTypeGrid from './type-grid.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

interface GridEvents {
  cellClick?: (args: { row: { type: string } }) => void;
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
    createTime: new Date('2026-09-01T00:00:00Z'),
    id: 1,
    name: '用户性别',
    remark: '',
    status: 0,
    type: 'sex',
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

vi.mock('#/api/system/dict/type', () => ({
  deleteDictType: vi.fn(),
  deleteDictTypeList: vi.fn(),
  exportDictType: vi.fn(),
  getDictTypePage: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showConfirmDialog: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('../data', () => ({
  useTypeGridColumns: vi.fn(() => []),
  useTypeGridFormSchema: vi.fn(() => []),
}));

vi.mock('./type-form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'TypeFormStub', template: '<div />' }),
  };
});

function mountGrid() {
  return mount(DictTypeGrid, { attachTo: document.body });
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

describe('dict type grid', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.gridConfig = undefined;
    state.gridEvents = undefined;
    vi.mocked(getDictTypePage).mockResolvedValue({
      list: [state.row],
      total: 1,
    });
  });

  it('queries dict types with paging and search values', async () => {
    mountGrid();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 1, pageSize: 10 } }, { name: 'x' });

    expect(getDictTypePage).toHaveBeenCalledWith({
      name: 'x',
      pageNo: 1,
      pageSize: 10,
    });
  });

  it('emits the selected row type on cell click', () => {
    const wrapper = mountGrid();

    (state.gridEvents?.cellClick as (args: { row: { type: string } }) => void)({
      row: { type: 'sex' },
    });

    expect(wrapper.emitted('select')).toEqual([['sex']]);
  });

  it('creates, exports and deletes dict types', async () => {
    const wrapper = mountGrid();
    state.gridApi.formApi.getValues.mockResolvedValue({ name: 'x' });
    vi.mocked(exportDictType).mockResolvedValue(new Blob(['xls']));

    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(null);
    expect(state.modalApi.open).toHaveBeenCalledOnce();

    await actionButton(wrapper, 'ui.actionTitle.export').trigger('click');
    await flushPromises();
    expect(exportDictType).toHaveBeenCalledWith({ name: 'x' });
    expect(state.downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: '字典类型.xls',
      source: expect.any(Blob),
    });

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();
    expect(deleteDictType).toHaveBeenCalledWith(1);

    state.gridEvents?.checkboxChange?.({
      records: [
        { id: 1, name: '用户性别' },
        { id: 2, name: '通知类型' },
      ],
    });
    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();
    expect(deleteDictTypeList).toHaveBeenCalledWith([1, 2]);
  });
});
