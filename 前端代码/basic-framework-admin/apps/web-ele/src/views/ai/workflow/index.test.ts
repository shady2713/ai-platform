import type { AiWorkflowApi } from '#/api/ai/workflow';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteWorkflow,
  getWorkflowPage,
  updateWorkflowStatus,
} from '#/api/ai/workflow';
import { showSuccessMessage } from '#/utils/feedback';

import WorkflowIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

interface ModalApi {
  open: ReturnType<typeof vi.fn>;
  setData: ReturnType<typeof vi.fn>;
}

/** 三个 useVbenModal 按声明顺序建 api，用下标区分是哪个面板被打开 */
const FORM_MODAL = 0;
const VERSIONS_MODAL = 1;
const RUNS_MODAL = 2;

const state = vi.hoisted(() => ({
  gridApi: { query: vi.fn() },
  gridConfig: undefined as unknown,
  modals: [] as unknown[],
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
    useVbenModal: vi.fn(() => {
      const setData = vi.fn();
      const open = vi.fn();
      const api = { open, setData };
      setData.mockReturnValue(api);
      state.modals.push(api);
      return [
        defineComponent({
          name: 'ModalStub',
          setup(_props, { slots }) {
            return () => h('section', slots.default?.());
          },
        }),
        api,
      ];
    }),
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

vi.mock('#/api/ai/workflow', () => ({
  deleteWorkflow: vi.fn(),
  getWorkflowPage: vi.fn(),
  updateWorkflowStatus: vi.fn(),
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
vi.mock('./modules/runs.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'RunsStub', template: '<div />' }),
  };
});

function workflow(
  overrides: Partial<AiWorkflowApi.Workflow> = {},
): AiWorkflowApi.Workflow {
  return {
    applicationId: 71,
    code: 'order-summary-flow',
    id: 81,
    name: '订单摘要生成流程',
    status: 'ENABLED',
    version: 3,
    ...overrides,
  };
}

function mountPage() {
  state.modals.length = 0;
  return mount(WorkflowIndex, { attachTo: document.body });
}

function actionButton(wrapper: ReturnType<typeof mountPage>, label: string) {
  return wrapper.element.querySelector(
    `button[data-action="${label}"]`,
  ) as HTMLButtonElement | null;
}

function modal(index: number) {
  return state.modals[index] as ModalApi;
}

describe('ai workflow page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = workflow();
  });

  it('各入口按后端 @PreAuthorize 原值授权', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(actionButton(wrapper, '版本管理')?.dataset.auth).toBe(
      'ai:workflow:query',
    );
    expect(actionButton(wrapper, '受理运行')?.dataset.auth).toBe(
      'ai:workflow:run',
    );
    expect(actionButton(wrapper, '运行记录')?.dataset.auth).toBe(
      'ai:workflow:query',
    );
    expect(actionButton(wrapper, '停用')?.dataset.auth).toBe(
      'ai:workflow:manage',
    );
    expect(actionButton(wrapper, 'common.edit')?.dataset.auth).toBe(
      'ai:workflow:manage',
    );
    expect(actionButton(wrapper, 'ui.actionTitle.create')?.dataset.auth).toBe(
      'ai:workflow:manage',
    );
    expect(actionButton(wrapper, 'common.delete')?.dataset.auth).toBe(
      'ai:workflow:delete',
    );
  });

  it('启用中的流程点停用，发 enabled:false', async () => {
    vi.mocked(updateWorkflowStatus).mockResolvedValue(true);
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '停用')?.dispatchEvent(new Event('click'));
    await flushPromises();
    expect(updateWorkflowStatus).toHaveBeenCalledWith({
      enabled: false,
      id: 81,
      version: 3,
    });
  });

  it('停用中的流程点启用，发 enabled:true', async () => {
    vi.mocked(updateWorkflowStatus).mockResolvedValue(true);
    state.row = workflow({ status: 'DISABLED' });
    const wrapper = mountPage();
    await flushPromises();

    expect(actionButton(wrapper, '启用')).not.toBeNull();
    actionButton(wrapper, '启用')?.dispatchEvent(new Event('click'));
    await flushPromises();
    expect(updateWorkflowStatus).toHaveBeenLastCalledWith({
      enabled: true,
      id: 81,
      version: 3,
    });
  });

  it('删除带乐观锁版本并提示成功', async () => {
    vi.mocked(deleteWorkflow).mockResolvedValue(true);
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, 'common.delete')?.dispatchEvent(new Event('click'));
    await flushPromises();
    expect(deleteWorkflow).toHaveBeenCalledWith(81, 3);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.deleteSuccess',
    );
  });

  it('分页查询带过滤条件，空结果不报错', async () => {
    vi.mocked(getWorkflowPage).mockResolvedValue({ list: [], total: 0 });
    const wrapper = mountPage();
    await flushPromises();

    const query = (state.gridConfig as GridConfig).gridOptions.proxyConfig.ajax
      .query;
    await query(
      { page: { currentPage: 2, pageSize: 20 } },
      { applicationId: 71, code: 'order', status: 'ENABLED' },
    );
    expect(getWorkflowPage).toHaveBeenCalledWith({
      applicationId: 71,
      code: 'order',
      pageNo: 2,
      pageSize: 20,
      status: 'ENABLED',
    });
    expect(wrapper.text()).not.toContain('undefined');
  });

  it('版本管理打开版本面板并传入当前行', async () => {
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '版本管理')?.dispatchEvent(new Event('click'));
    expect(modal(VERSIONS_MODAL).setData).toHaveBeenCalledWith(state.row);
    expect(modal(VERSIONS_MODAL).open).toHaveBeenCalled();
  });

  it('受理运行与只读查看走同一个面板但带不同意图', async () => {
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '受理运行')?.dispatchEvent(new Event('click'));
    expect(modal(RUNS_MODAL).setData).toHaveBeenCalledWith({
      accept: true,
      workflow: state.row,
    });

    actionButton(wrapper, '运行记录')?.dispatchEvent(new Event('click'));
    expect(modal(RUNS_MODAL).setData).toHaveBeenLastCalledWith({
      accept: false,
      workflow: state.row,
    });
  });

  it('新增与编辑打开表单面板', async () => {
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, 'ui.actionTitle.create')?.dispatchEvent(
      new Event('click'),
    );
    expect(modal(FORM_MODAL).setData).toHaveBeenCalledWith({});

    actionButton(wrapper, 'common.edit')?.dispatchEvent(new Event('click'));
    expect(modal(FORM_MODAL).setData).toHaveBeenLastCalledWith(state.row);
  });
});
