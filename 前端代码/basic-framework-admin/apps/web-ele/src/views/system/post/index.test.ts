import type { SystemPostApi } from '#/api/system/post';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deletePost,
  deletePostList,
  exportPost,
  getPostPage,
} from '#/api/system/post';
import { showConfirmDialog } from '#/utils/feedback';

import PostIndex from './index.vue';

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
  downloadFileFromBlobPart: vi.fn(),
  gridApi: {
    formApi: { getValues: vi.fn() },
    query: vi.fn(),
  },
  gridConfig: undefined as GridConfig | undefined,
  gridEvents: undefined as GridEvents | undefined,
  modalApi: {
    open: vi.fn(),
    setData: vi.fn(),
  },
  row: {} as SystemPostApi.Post,
}));

state.modalApi.setData.mockImplementation(() => state.modalApi);

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
        emits: ['success'],
        setup(_props, { emit }) {
          return () =>
            h(
              'button',
              { 'data-test': 'modal-success', onClick: () => emit('success') },
              '完成',
            );
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
      state.gridConfig = options;
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

vi.mock('#/api/system/post', () => ({
  deletePost: vi.fn(),
  deletePostList: vi.fn(),
  exportPost: vi.fn(),
  getPostPage: vi.fn(),
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
    default: defineComponent({ name: 'PostFormStub', template: '<div />' }),
  };
});

function post(overrides: Partial<SystemPostApi.Post> = {}): SystemPostApi.Post {
  return {
    code: 'auditor',
    id: 4,
    name: '审计专员',
    remark: '',
    sort: 1,
    status: 0,
    ...overrides,
  };
}

function mountPage() {
  return mount(PostIndex, { attachTo: document.body });
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

describe('system post page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = post();
    state.gridConfig = undefined;
    state.gridEvents = undefined;
    state.modalApi.setData.mockImplementation(() => state.modalApi);
    vi.mocked(getPostPage).mockResolvedValue({ list: [post()], total: 1 });
    state.gridApi.formApi.getValues.mockResolvedValue({ name: '审计' });
    vi.mocked(exportPost).mockResolvedValue(new Blob(['xls']));
  });

  it('queries posts with paging and search values', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 3, pageSize: 20 } }, { name: 'x' });

    expect(getPostPage).toHaveBeenCalledWith({
      name: 'x',
      pageNo: 3,
      pageSize: 20,
    });
  });

  it('creates and edits a post through the form modal', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(null);
    expect(state.modalApi.open).toHaveBeenCalledOnce();

    await actionButton(wrapper, 'common.edit').trigger('click');
    expect(state.modalApi.setData).toHaveBeenLastCalledWith(state.row);
  });

  it('exports the search values as an xls download', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.export').trigger('click');
    await flushPromises();

    expect(exportPost).toHaveBeenCalledWith({ name: '审计' });
    expect(state.downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: '岗位.xls',
      source: expect.any(Blob),
    });
  });

  it('deletes a post after confirmation and refreshes the grid', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();

    expect(deletePost).toHaveBeenCalledWith(4);
    expect(state.gridApi.query).toHaveBeenCalledOnce();
    expect(state.closeLoading).toHaveBeenCalledOnce();
  });

  it('batch deletes the checked posts and skips when cancelled', async () => {
    const wrapper = mountPage();

    state.gridEvents?.checkboxChange?.({
      records: [post({ id: 4 }), post({ id: 5 })],
    });
    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();
    expect(deletePostList).toHaveBeenCalledWith([4, 5]);

    vi.mocked(showConfirmDialog).mockRejectedValueOnce(new Error('cancel'));
    state.gridEvents?.checkboxChange?.({ records: [post({ id: 6 })] });
    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();
    expect(deletePostList).toHaveBeenCalledTimes(1);
  });

  it('refreshes the grid when the form modal reports success', async () => {
    const wrapper = mountPage();

    await wrapper.find('[data-test="modal-success"]').trigger('click');
    await flushPromises();

    expect(state.gridApi.query).toHaveBeenCalledOnce();
  });
});
