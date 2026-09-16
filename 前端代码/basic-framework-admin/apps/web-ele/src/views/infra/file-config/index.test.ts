import type { InfraFileConfigApi } from '#/api/infra/file-config';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteFileConfig,
  deleteFileConfigList,
  getFileConfigPage,
  testFileConfig,
  updateFileConfigMaster,
} from '#/api/infra/file-config';
import { showSuccessMessage } from '#/utils/feedback';

import FileConfigIndex from './index.vue';

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
  confirm: vi.fn(),
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
  row: {} as InfraFileConfigApi.FileConfig,
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
    confirm: state.confirm,
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
      ADD: 'lucide:plus',
      DELETE: 'lucide:trash-2',
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

vi.mock('#/api/infra/file-config', () => ({
  deleteFileConfig: vi.fn(),
  deleteFileConfigList: vi.fn(),
  getFileConfigPage: vi.fn(),
  testFileConfig: vi.fn(),
  updateFileConfigMaster: vi.fn(),
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
    default: defineComponent({
      name: 'FileConfigFormStub',
      template: '<div />',
    }),
  };
});

function fileConfig(
  overrides: Partial<InfraFileConfigApi.FileConfig> = {},
): InfraFileConfigApi.FileConfig {
  return {
    config: { basePath: '/data/files', domain: 'http://cdn.example' },
    id: 1,
    master: false,
    name: '本地存储',
    remark: '',
    visible: true,
    ...overrides,
  };
}

function mountPage() {
  return mount(FileConfigIndex, { attachTo: document.body });
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

describe('infra file-config page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = fileConfig();
    state.gridConfig = undefined;
    state.gridEvents = undefined;
    state.confirm.mockResolvedValue('confirm');
    vi.mocked(getFileConfigPage).mockResolvedValue({
      list: [state.row],
      total: 1,
    });
    vi.mocked(testFileConfig).mockResolvedValue('http://cdn.example/f.txt');
    vi.mocked(updateFileConfigMaster).mockResolvedValue(undefined);
  });

  it('queries file configs with paging and search values', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 1, pageSize: 10 } }, { name: 'x' });

    expect(getFileConfigPage).toHaveBeenCalledWith({
      name: 'x',
      pageNo: 1,
      pageSize: 10,
    });
  });

  it('tests a config and opens the returned file after confirmation', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '测试').trigger('click');
    await flushPromises();

    expect(testFileConfig).toHaveBeenCalledWith(1);
    expect(state.confirm).toHaveBeenCalledOnce();
    expect(state.openWindow).toHaveBeenCalledWith('http://cdn.example/f.txt');
    expect(state.closeLoading).toHaveBeenCalledOnce();
  });

  it('keeps the file closed when the user cancels the visit dialog', async () => {
    state.confirm.mockRejectedValue(new Error('user cancelled'));
    const wrapper = mountPage();

    await actionButton(wrapper, '测试').trigger('click');
    await flushPromises();

    expect(state.openWindow).not.toHaveBeenCalled();
    expect(state.closeLoading).toHaveBeenCalledOnce();
  });

  it('promotes a config to master with a loading indicator', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '主配置').trigger('click');
    await flushPromises();

    expect(updateFileConfigMaster).toHaveBeenCalledWith(1);
    expect(showSuccessMessage).toHaveBeenCalled();
    expect(state.gridApi.query).toHaveBeenCalled();
    expect(state.closeLoading).toHaveBeenCalledOnce();
  });

  it('deletes the selected row after confirmation', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();

    expect(deleteFileConfig).toHaveBeenCalledWith(1);
  });

  it('batch deletes the checked rows', async () => {
    const wrapper = mountPage();
    state.gridEvents?.checkboxChange?.({
      records: [fileConfig({ id: 1 }), fileConfig({ id: 2 })],
    });

    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();

    expect(deleteFileConfigList).toHaveBeenCalledWith([1, 2]);
  });
});
