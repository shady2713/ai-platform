import type { InfraJobApi } from '#/api/infra/job';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteJob,
  deleteJobList,
  exportJob,
  getJobPage,
  runJob,
  updateJobStatus,
} from '#/api/infra/job';
import { showSuccessMessage } from '#/utils/feedback';

import JobIndex from './index.vue';

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
  push: vi.fn(),
  row: {} as InfraJobApi.Job,
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: state.push }),
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

vi.mock('@vben/constants', () => ({
  InfraJobStatusEnum: { INIT: 0, NORMAL: 1, STOP: 2 },
}));

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

vi.mock('#/api/infra/job', () => ({
  deleteJob: vi.fn(),
  deleteJobList: vi.fn(),
  exportJob: vi.fn(),
  getJobPage: vi.fn(),
  runJob: vi.fn(),
  updateJobStatus: vi.fn(),
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
    default: defineComponent({ name: 'JobFormStub', template: '<div />' }),
  };
});

vi.mock('./modules/detail.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'JobDetailStub', template: '<div />' }),
  };
});

function job(overrides: Partial<InfraJobApi.Job> = {}): InfraJobApi.Job {
  return {
    cronExpression: '0 * * * * ?',
    handlerName: 'demoTask',
    handlerParam: '',
    id: 1,
    monitorTimeout: 0,
    name: '同步任务',
    retryCount: 3,
    retryInterval: 60,
    status: 2,
    ...overrides,
  };
}

function mountPage() {
  return mount(JobIndex, { attachTo: document.body });
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

describe('infra job page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = job();
    state.gridConfig = undefined;
    state.gridEvents = undefined;
    state.confirm.mockResolvedValue('confirm');
    vi.mocked(getJobPage).mockResolvedValue({
      list: [job()],
      total: 1,
    });
  });

  it('queries the job page with paging and search values', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 1, pageSize: 20 } }, { name: 'x' });

    expect(getJobPage).toHaveBeenCalledWith({
      name: 'x',
      pageNo: 1,
      pageSize: 20,
    });
  });

  it('exports the search values as an xls download', async () => {
    const wrapper = mountPage();
    state.gridApi.formApi.getValues.mockResolvedValue({ status: 2, name: 'x' });
    vi.mocked(exportJob).mockResolvedValue(new Blob(['xls']));

    await actionButton(wrapper, 'ui.actionTitle.export').trigger('click');
    await flushPromises();

    expect(exportJob).toHaveBeenCalledWith({ name: 'x', status: 2 });
    expect(state.downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: '定时任务.xls',
      source: expect.any(Blob),
    });
  });

  it('opens the log list and toggles a stopped job to running', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '执行日志').trigger('click');
    expect(state.push).toHaveBeenCalledWith({
      name: 'InfraJobLog',
      query: {},
    });

    await actionButton(wrapper, '开启').trigger('click');
    await flushPromises();

    expect(state.confirm).toHaveBeenCalledWith('确定启用 同步任务 吗？');
    expect(updateJobStatus).toHaveBeenCalledWith(1, 1);
    expect(showSuccessMessage).toHaveBeenCalled();
    expect(state.gridApi.query).toHaveBeenCalled();
    expect(state.closeLoading).toHaveBeenCalledOnce();
  });

  it('pauses a running job and runs it once on demand', async () => {
    state.row = job({ status: 1 });
    const wrapper = mountPage();

    await actionButton(wrapper, '暂停').trigger('click');
    await flushPromises();
    expect(updateJobStatus).toHaveBeenCalledWith(1, 2);

    await actionButton(wrapper, '执行').trigger('click');
    await flushPromises();
    expect(runJob).toHaveBeenCalledWith(1);
  });

  it('opens the detail and per-job log from the drop-down actions', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.detail').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith({ id: 1 });
    expect(state.modalApi.open).toHaveBeenCalledOnce();

    await actionButton(wrapper, '日志').trigger('click');
    expect(state.push).toHaveBeenCalledWith({
      name: 'InfraJobLog',
      query: { id: 1 },
    });
  });

  it('edits, deletes and batch deletes jobs', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.edit').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();
    expect(deleteJob).toHaveBeenCalledWith(1);

    state.gridEvents?.checkboxChange?.({
      records: [job({ id: 1 }), job({ id: 2 })],
    });
    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();
    expect(deleteJobList).toHaveBeenCalledWith([1, 2]);
  });
});
