import type { InfraFileApi } from '#/api/infra/file';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteFile,
  deleteFileList,
  fetchFileContent,
  getFilePage,
} from '#/api/infra/file';
import { showErrorMessage, showSuccessMessage } from '#/utils/feedback';

import FileIndex from './index.vue';

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
  copy: vi.fn(),
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
  openWindow: vi.fn(),
  row: {} as InfraFileApi.File,
}));

vi.mock('@vueuse/core', () => ({
  useClipboard: () => ({ copy: state.copy }),
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
  isEmpty: (value: unknown) =>
    Array.isArray(value) ? value.length === 0 : !value,
  openWindow: state.openWindow,
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
      COPY: 'lucide:copy',
      DELETE: 'lucide:trash-2',
      UPLOAD: 'lucide:upload',
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
                slots['file-content']?.({ row: state.row }),
                slots.actions?.({ row: state.row }),
              ]);
          },
        }),
        state.gridApi,
      ];
    }),
  };
});

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    ElButton: defineComponent({
      name: 'ElButton',
      props: {
        link: { type: Boolean, default: false },
        type: { type: String, default: 'primary' },
      },
      emits: ['click'],
      setup(_props, { emit, slots }) {
        return () =>
          h('button', { onClick: () => emit('click') }, slots.default?.());
      },
    }),
    ElImage: defineComponent({
      name: 'ElImage',
      props: { src: { type: String, default: '' } },
      setup(props) {
        return () => h('img', { 'data-test': 'file-image', src: props.src });
      },
    }),
    ElLoading: { service: vi.fn(() => ({ close: state.closeLoading })) },
  };
});

vi.mock('#/api/infra/file', () => ({
  deleteFile: vi.fn(),
  deleteFileList: vi.fn(),
  fetchFileContent: vi.fn(),
  FILE_ACCESS_TYPE: { PRIVATE: 2, PUBLIC: 1 },
  getFilePage: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showConfirmDialog: vi.fn(),
  showErrorMessage: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('./data', () => ({
  useGridColumns: vi.fn(() => []),
  useGridFormSchema: vi.fn(() => []),
}));

vi.mock('./modules/form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'UploadFormStub', template: '<div />' }),
  };
});

vi.mock('./modules/file-image.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    default: defineComponent({
      name: 'FileImageStub',
      props: { row: { type: Object, required: true } },
      setup(props) {
        return () =>
          h('img', { 'data-test': 'file-image', src: props.row.url });
      },
    }),
  };
});

function file(overrides: Partial<InfraFileApi.File> = {}): InfraFileApi.File {
  return {
    accessType: 1,
    id: 1,
    name: '报告.pdf',
    path: '/2026/09/f.pdf',
    type: 'application/pdf',
    url: 'http://cdn.example/f.pdf',
    ...overrides,
  };
}

function mountPage() {
  return mount(FileIndex, { attachTo: document.body });
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

describe('infra file page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = file();
    state.gridConfig = undefined;
    state.gridEvents = undefined;
    state.copy.mockResolvedValue(undefined);
    vi.mocked(getFilePage).mockResolvedValue({
      list: [file()],
      total: 1,
    });
  });

  it('queries file pages with paging and search values', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 3, pageSize: 20 } }, { name: 'x' });

    expect(getFilePage).toHaveBeenCalledWith({
      name: 'x',
      pageNo: 3,
      pageSize: 20,
    });
  });

  it('copies the file URL to the clipboard', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '复制链接').trigger('click');
    await flushPromises();

    expect(state.copy).toHaveBeenCalledWith('http://cdn.example/f.pdf');
    expect(showSuccessMessage).toHaveBeenCalledWith('复制成功');
  });

  it('rejects copying without a URL', async () => {
    state.row = file({ url: undefined });
    const wrapper = mountPage();

    await actionButton(wrapper, '复制链接').trigger('click');

    expect(state.copy).not.toHaveBeenCalled();
    expect(showErrorMessage).toHaveBeenCalledWith('文件 URL 为空');
  });

  it('reports a failure when the clipboard rejects', async () => {
    state.copy.mockRejectedValue(new Error('denied'));
    const wrapper = mountPage();

    await actionButton(wrapper, '复制链接').trigger('click');
    await flushPromises();

    expect(state.copy).toHaveBeenCalledWith('http://cdn.example/f.pdf');
    expect(showErrorMessage).toHaveBeenCalledWith('复制失败');
  });

  it('reports a failure when opening a private file rejects', async () => {
    state.row = file({ accessType: 2 });
    vi.mocked(fetchFileContent).mockRejectedValue(new Error('401'));
    const wrapper = mountPage();

    const preview = wrapper
      .findAll('button')
      .find((candidate) => candidate.text() === '预览');
    await preview?.trigger('click');
    await flushPromises();

    expect(showErrorMessage).toHaveBeenCalledWith('文件打开失败');
  });

  it('renders an image preview and labels the open action as preview', async () => {
    state.row = file({ type: 'image/png', url: 'http://cdn.example/a.png' });
    const wrapper = mountPage();

    const image = wrapper.find('[data-test="file-image"]');
    expect(image.attributes('src')).toBe('http://cdn.example/a.png');

    const preview = wrapper
      .findAll('button')
      .find((candidate) => candidate.text() === '预览');
    expect(preview).toBeDefined();
    await preview?.trigger('click');
    expect(state.openWindow).toHaveBeenCalledWith('http://cdn.example/a.png');
  });

  it('labels pdfs as preview and deletes the selected row', async () => {
    const wrapper = mountPage();

    const preview = wrapper
      .findAll('button')
      .find((candidate) => candidate.text() === '预览');
    expect(preview).toBeDefined();
    await preview?.trigger('click');
    expect(state.openWindow).toHaveBeenCalledWith('http://cdn.example/f.pdf');

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();
    expect(deleteFile).toHaveBeenCalledWith(1);
  });

  it('batch deletes the checked rows', async () => {
    const wrapper = mountPage();
    state.gridEvents?.checkboxChange?.({
      records: [file({ id: 1 }), file({ id: 2 })],
    });

    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();

    expect(deleteFileList).toHaveBeenCalledWith([1, 2]);
  });
});
