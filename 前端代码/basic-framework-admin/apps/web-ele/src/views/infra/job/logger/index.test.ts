import type { InfraJobLogApi } from '#/api/infra/job-log';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { exportJobLog, getJobLogPage } from '#/api/infra/job-log';

import JobLoggerIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

function jobLog(): InfraJobLogApi.JobLog {
  return {
    beginTime: new Date('2026-09-01T10:00:00Z'),
    cronExpression: '0 * * * * ?',
    duration: '1000ms',
    endTime: new Date('2026-09-01T10:00:01Z'),
    executeIndex: '1',
    handlerName: 'demoTask',
    handlerParam: '',
    id: 9,
    jobId: 5,
    result: 'success',
    status: 0,
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
  route: { query: { id: '5' } },
}));

vi.mock('vue-router', () => ({
  useRoute: () => state.route,
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
              h('div', { 'data-test': 'grid' }, [
                slots.default?.(),
                slots['toolbar-tools']?.(),
                slots.actions?.({ row: { id: 9 } }),
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

vi.mock('#/api/infra/job-log', () => ({
  exportJobLog: vi.fn(),
  getJobLogPage: vi.fn(),
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
  return mount(JobLoggerIndex, { attachTo: document.body });
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

describe('infra job logger page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.gridConfig = undefined;
    vi.mocked(getJobLogPage).mockResolvedValue({
      list: [jobLog()],
      total: 1,
    });
  });

  it('queries logs for the job id from the route', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 1, pageSize: 20 } }, { name: 'x' });

    expect(getJobLogPage).toHaveBeenCalledWith({
      jobId: '5',
      name: 'x',
      pageNo: 1,
      pageSize: 20,
    });
  });

  it('exports the log search values as an xls download', async () => {
    const wrapper = mountPage();
    state.gridApi.formApi.getValues.mockResolvedValue({ jobId: '5' });
    vi.mocked(exportJobLog).mockResolvedValue(new Blob(['xls']));

    await actionButton(wrapper, 'ui.actionTitle.export').trigger('click');
    await flushPromises();

    expect(exportJobLog).toHaveBeenCalledWith({ jobId: '5' });
    expect(state.downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: '任务日志.xls',
      source: expect.any(Blob),
    });
  });

  it('opens the log detail with the selected row id', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.detail').trigger('click');

    expect(state.modalApi.setData).toHaveBeenCalledWith({ id: 9 });
    expect(state.modalApi.open).toHaveBeenCalledOnce();
  });
});
