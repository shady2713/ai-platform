import type { AiApplicationApi } from '#/api/ai/application';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteApplication,
  getApplicationPage,
  revokeCredential,
  updateApplicationStatus,
} from '#/api/ai/application';
import { showSuccessMessage } from '#/utils/feedback';

import ApplicationIndex from './index.vue';

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
  row: {} as AiApplicationApi.Application,
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
    useVbenModal: vi.fn((config?: { connectedComponent?: unknown }) => [
      defineComponent({
        name: 'ModalStub',
        setup(_props, { attrs, slots, expose }) {
          expose({ open: state.modalApi.open });
          // 渲染连接组件，并把内部组件抛出的 secret 事件转发给页面（等价于真实弹窗的事件透传）
          const forwardSecret = (payload: unknown) => {
            const onSecret = attrs.onSecret as
              | ((value: unknown) => void)
              | undefined;
            onSecret?.(payload);
          };
          return () =>
            h(
              'section',
              config?.connectedComponent
                ? h(config.connectedComponent as never, {
                    onSecret: forwardSecret,
                  })
                : slots.default?.(),
            );
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

vi.mock('#/api/ai/application', () => ({
  deleteApplication: vi.fn(),
  getApplicationPage: vi.fn(),
  revokeCredential: vi.fn(),
  updateApplicationStatus: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));
vi.mock('./data', () => ({
  AI_APPLICATION_PERMISSIONS: {
    create: 'ai:application:create',
    delete: 'ai:application:delete',
    query: 'ai:application:query',
    revoke: 'ai:application:revoke',
    rotate: 'ai:application:rotate',
    update: 'ai:application:update',
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
vi.mock('./modules/rotate.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'RotateStub', template: '<div />' }),
  };
});
vi.mock('./modules/secret.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'SecretStub', template: '<div />' }),
  };
});

function row(
  overrides: Partial<AiApplicationApi.Application> = {},
): AiApplicationApi.Application {
  return {
    appCode: 'crm-portal',
    credentialConfigured: true,
    description: '',
    enabled: true,
    id: 5,
    name: 'CRM 门户',
    origins: ['https://crm.example.com'],
    version: 2,
    ...overrides,
  };
}

function queryApplication() {
  return (state.gridConfig as GridConfig)?.gridOptions.proxyConfig.ajax.query;
}

function mountPage() {
  return mount(ApplicationIndex, { attachTo: document.body });
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

describe('ai application page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = row();
    state.gridConfig = undefined;
    vi.mocked(getApplicationPage).mockResolvedValue({
      list: [row()],
      total: 1,
    });
  });

  it('queries with paging and search values', async () => {
    mountPage();

    await queryApplication()?.(
      { page: { currentPage: 3, pageSize: 50 } },
      { appCode: 'crm' },
    );

    expect(getApplicationPage).toHaveBeenCalledWith({
      appCode: 'crm',
      pageNo: 3,
      pageSize: 50,
    });
  });

  it('opens create and edit modals and routes the one-time secret to the secret modal', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith({});

    await actionButton(wrapper, 'common.edit').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);

    // 表单/轮换组件抛出一次性秘密 → 页面把它交给秘密弹窗（父组件不长期持有）
    const payload = { appCode: 'crm-portal', secret: 'aiapp_once' };
    wrapper.findComponent({ name: 'FormStub' }).vm.$emit('secret', payload);
    await flushPromises();
    expect(state.modalApi.setData).toHaveBeenCalledWith(payload);
    expect(showSuccessMessage).toHaveBeenCalledWith('凭据已签发，请立即保存');
  });

  it('toggles status, revokes credential and deletes with the current version', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '停用').trigger('click');
    await flushPromises();
    expect(updateApplicationStatus).toHaveBeenCalledWith(5, 2, false);

    await actionButton(wrapper, '吊销凭据').trigger('click');
    await flushPromises();
    expect(revokeCredential).toHaveBeenCalledWith(5, 2);

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();
    expect(deleteApplication).toHaveBeenCalledWith(5, 2);
    expect(state.gridApi.query).toHaveBeenCalled();
  });

  it('opens rotation modal for the selected row', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '轮换凭据').trigger('click');

    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);
  });
});
