import type { AiServiceApi } from '#/api/ai/service';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  checkServiceCapabilities,
  deleteService,
  getServicePage,
  markServiceReady,
} from '#/api/ai/service';
import { showSuccessMessage } from '#/utils/feedback';

import ServiceIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

const state = vi.hoisted(() => ({
  gridApi: { query: vi.fn() },
  gridConfig: undefined as unknown,
  modalApi: { open: vi.fn(), setData: vi.fn(() => state.modalApi) },
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
    ACTION_ICON: {
      ADD: 'lucide:plus',
      DELETE: 'lucide:trash-2',
      EDIT: 'lucide:edit',
    },
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

vi.mock('#/api/ai/service', () => ({
  checkServiceCapabilities: vi.fn(),
  deleteService: vi.fn(),
  getServicePage: vi.fn(),
  markServiceReady: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));
vi.mock('./data', () => ({
  AI_SERVICE_PERMISSIONS: {
    activate: 'ai:service:activate',
    bind: 'ai:service:bind',
    create: 'ai:service:create',
    debug: 'ai:service:debug',
    delete: 'ai:service:delete',
    evaluate: 'ai:service:evaluate',
    publish: 'ai:service:publish',
    query: 'ai:service:query',
    release: 'ai:service:release',
    update: 'ai:service:update',
  },
  useGridColumns: vi.fn(() => []),
  useGridFormSchema: vi.fn(() => []),
}));
vi.mock('./modules/form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'FormStub', template: '<div />' }),
  };
});
vi.mock('./modules/release.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'ReleaseStub', template: '<div />' }),
  };
});
vi.mock('./modules/versions.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'VersionsStub', template: '<div />' }),
  };
});
vi.mock('./modules/debug.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'DebugStub', template: '<div />' }),
  };
});

function service(
  overrides: Partial<AiServiceApi.Service> = {},
): AiServiceApi.Service {
  return {
    appId: 5,
    code: 'svc_order_qa',
    draftRevision: 3,
    evalThreshold: 80,
    id: 9,
    inputSchema: '{"type":"object"}',
    modelEndpointId: 1,
    name: '订单问答',
    promptTemplate: '你是订单助手',
    requiredCapabilities: ['TEXT'],
    runSubjectType: 'USER',
    status: 'READY',
    version: 4,
    ...overrides,
  };
}

function actionButton(wrapper: ReturnType<typeof mountPage>, label: string) {
  // 只在当前挂载实例内查找：多个用例都 attachTo body，全局查询会命中上一个用例的残留 DOM
  return wrapper.element.querySelector(
    `button[data-action="${label}"]`,
  ) as HTMLButtonElement | null;
}

function authOf(wrapper: ReturnType<typeof mountPage>, label: string) {
  return actionButton(wrapper, label)?.dataset.auth;
}

function queryService() {
  return (state.gridConfig as GridConfig)?.gridOptions.proxyConfig.ajax.query;
}

function mountPage() {
  return mount(ServiceIndex, { attachTo: document.body });
}

describe('ai service page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = service();
    state.modalApi.setData.mockReturnValue(state.modalApi);
  });

  it('发布与回退入口受权限码控制，无发布权看不到入口', async () => {
    const wrapper = mountPage();
    await flushPromises();

    // 权限码与 V56–V58 迁移种子一致：前端隐藏入口，后端仍二次校验
    expect(authOf(wrapper, '版本历史与回退')).toBe('ai:service:activate');
    expect(authOf(wrapper, '发布与评测')).toBe('ai:service:release');
    expect(authOf(wrapper, '标记可发布')).toBe('ai:service:publish');
    expect(authOf(wrapper, '调试')).toBe('ai:service:debug');
    expect(authOf(wrapper, 'common.delete')).toBe('ai:service:delete');
    expect(authOf(wrapper, 'ui.actionTitle.create')).toBe('ai:service:create');
  });

  it('打开发布/版本/调试弹窗时传入当前行', async () => {
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '发布与评测')?.dispatchEvent(new Event('click'));
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);
    expect(state.modalApi.open).toHaveBeenCalled();

    actionButton(wrapper, '版本历史与回退')?.dispatchEvent(new Event('click'));
    actionButton(wrapper, '调试')?.dispatchEvent(new Event('click'));
    expect(state.modalApi.open).toHaveBeenCalledTimes(3);
  });

  it('能力不足时不标记可发布并提示缺失能力', async () => {
    vi.mocked(checkServiceCapabilities).mockResolvedValue({
      missing: ['STRUCTURED_OUTPUT'],
      publishable: ['TEXT'],
      required: ['TEXT', 'STRUCTURED_OUTPUT'],
      satisfied: false,
    });
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '标记可发布')?.dispatchEvent(new Event('click'));
    await flushPromises();
    await flushPromises();

    expect(markServiceReady).not.toHaveBeenCalled();
    expect(wrapper.find('p.text-destructive').exists()).toBe(true);
    expect(wrapper.find('p.text-destructive').text()).toContain(
      'STRUCTURED_OUTPUT',
    );
  });

  it('能力满足时标记可发布并刷新列表', async () => {
    vi.mocked(checkServiceCapabilities).mockResolvedValue({
      missing: [],
      publishable: ['TEXT'],
      required: ['TEXT'],
      satisfied: true,
    });
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, '标记可发布')?.dispatchEvent(new Event('click'));
    await flushPromises();

    expect(markServiceReady).toHaveBeenCalledWith(9, 4);
    expect(state.gridApi.query).toHaveBeenCalled();
    expect(showSuccessMessage).toHaveBeenCalled();
  });

  it('删除走确认回调并刷新列表', async () => {
    vi.mocked(deleteService).mockResolvedValue(true);
    const wrapper = mountPage();
    await flushPromises();

    actionButton(wrapper, 'common.delete')?.dispatchEvent(new Event('click'));
    await flushPromises();

    expect(deleteService).toHaveBeenCalledWith(9, 4);
    expect(state.gridApi.query).toHaveBeenCalled();
  });

  it('列表查询带上分页与筛选条件', async () => {
    vi.mocked(getServicePage).mockResolvedValue({ list: [], total: 0 });
    mountPage();
    await flushPromises();

    await queryService()?.(
      { page: { currentPage: 2, pageSize: 20 } },
      { code: 'svc_order_qa' },
    );

    expect(getServicePage).toHaveBeenCalledWith({
      code: 'svc_order_qa',
      pageNo: 2,
      pageSize: 20,
    });
  });
});
