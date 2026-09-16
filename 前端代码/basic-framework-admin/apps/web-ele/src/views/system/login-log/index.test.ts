import type { SystemLoginLogApi } from '#/api/system/login-log';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { exportLoginLog, getLoginLogPage } from '#/api/system/login-log';

import LoginLogIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

function loginLog(): SystemLoginLogApi.LoginLog {
  return {
    createTime: '2026-09-01 10:00:00',
    id: 12,
    logType: 1,
    result: 0,
    status: 0,
    traceId: 1001,
    userAgent: 'Chrome',
    userId: 1,
    userIp: '127.0.0.1',
    username: 'admin',
    userType: 1,
  };
}

const state = vi.hoisted(() => ({
  downloadFileFromBlobPart: vi.fn(),
  gridApi: {
    formApi: { getValues: vi.fn() },
    query: vi.fn(),
  },
  gridConfig: undefined as GridConfig | undefined,
  modalApi: {
    open: vi.fn(),
    setData: vi.fn(() => state.modalApi),
  },
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
  downloadFileFromBlobPart: state.downloadFileFromBlobPart,
}));

vi.mock('#/adapter/vxe-table', async () => {
  const { defineComponent, h } = await import('vue');
  const renderAction = (action: Record<string, unknown>) => {
    const handler = action.onClick as () => unknown;
    return h(
      'button',
      { 'data-action': String(action.label), onClick: handler },
      String(action.label),
    );
  };
  return {
    ACTION_ICON: {
      DOWNLOAD: 'lucide:download',
      VIEW: 'lucide:eye',
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
      state.gridConfig = gridOptions as GridConfig;
      return [
        defineComponent({
          name: 'GridStub',
          setup(_props, { slots }) {
            return () =>
              h('div', [
                slots.default?.(),
                slots['toolbar-tools']?.(),
                slots.actions?.({ row: { id: 12, username: 'admin' } }),
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

vi.mock('#/api/system/login-log', () => ({
  exportLoginLog: vi.fn(),
  getLoginLogPage: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('./data', () => ({
  useGridColumns: vi.fn(() => []),
  useGridFormSchema: vi.fn(() => []),
}));

vi.mock('./modules/detail.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'LogDetailStub', template: '<div />' }),
  };
});

function mountPage() {
  return mount(LoginLogIndex, { attachTo: document.body });
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

describe('system login log page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.gridConfig = undefined;
    vi.mocked(getLoginLogPage).mockResolvedValue({
      list: [loginLog()],
      total: 1,
    });
    state.gridApi.formApi.getValues.mockResolvedValue({ username: 'admin' });
    vi.mocked(exportLoginLog).mockResolvedValue(new Blob(['xls']));
  });

  it('queries login logs with paging and search values', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.(
      { page: { currentPage: 1, pageSize: 20 } },
      { username: 'x' },
    );

    expect(getLoginLogPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      username: 'x',
    });
  });

  it('exports the search values as an xls download', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.export').trigger('click');
    await flushPromises();

    expect(exportLoginLog).toHaveBeenCalledWith({ username: 'admin' });
    expect(state.downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: '登录日志.xls',
      source: expect.any(Blob),
    });
  });

  it('opens the detail modal with the selected row', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.detail').trigger('click');

    expect(state.modalApi.setData).toHaveBeenCalledWith({
      id: 12,
      username: 'admin',
    });
    expect(state.modalApi.open).toHaveBeenCalledOnce();
  });
});
