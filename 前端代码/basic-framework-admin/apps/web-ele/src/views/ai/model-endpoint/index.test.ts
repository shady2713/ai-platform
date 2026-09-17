import type { AiModelEndpointApi } from '#/api/ai/model-endpoint';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteModelEndpoint,
  getModelEndpointPage,
  updateModelEndpointStatus,
} from '#/api/ai/model-endpoint';
import { showSuccessMessage } from '#/utils/feedback';

import ModelEndpointIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

const state = vi.hoisted(() => ({
  gridApi: { query: vi.fn() },
  gridConfig: undefined as GridConfig | undefined,
  modalApi: {
    open: vi.fn(),
    setData: vi.fn(() => state.modalApi),
  },
  row: {} as AiModelEndpointApi.ModelEndpoint,
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
      state.gridConfig = gridOptions as GridConfig;
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

vi.mock('#/api/ai/model-endpoint', () => ({
  deleteModelEndpoint: vi.fn(),
  getModelEndpointPage: vi.fn(),
  updateModelEndpointStatus: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));

vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));

vi.mock('./data', () => ({
  AI_MODEL_ENDPOINT_PERMISSIONS: {
    create: 'ai:model-endpoint:create',
    delete: 'ai:model-endpoint:delete',
    probe: 'ai:model-endpoint:probe',
    query: 'ai:model-endpoint:query',
    update: 'ai:model-endpoint:update',
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
vi.mock('./modules/probe.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'ProbeStub', template: '<div />' }),
  };
});

function row(
  overrides: Partial<AiModelEndpointApi.ModelEndpoint> = {},
): AiModelEndpointApi.ModelEndpoint {
  return {
    baseUrl: 'https://api.example.com/v1',
    capabilities: ['TEXT'],
    configRevision: 1,
    credentialConfigured: true,
    credentialRevision: 1,
    enabled: true,
    id: 9,
    modelId: 'gpt-4o-mini',
    name: 'openai-生产',
    provider: 'openai_compatible',
    referenced: false,
    version: 3,
    ...overrides,
  };
}

function queryEndpoint() {
  return state.gridConfig?.gridOptions.proxyConfig.ajax.query;
}

function mountPage() {
  return mount(ModelEndpointIndex, { attachTo: document.body });
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

describe('ai model endpoint page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = row();
    state.gridConfig = undefined;
    vi.mocked(getModelEndpointPage).mockResolvedValue({
      list: [row()],
      total: 1,
    });
  });

  it('queries the endpoint page with paging and search values', async () => {
    mountPage();

    await queryEndpoint()?.(
      { page: { currentPage: 2, pageSize: 20 } },
      { name: 'openai' },
    );

    expect(getModelEndpointPage).toHaveBeenCalledWith({
      name: 'openai',
      pageNo: 2,
      pageSize: 20,
    });
  });

  it('opens create and edit modals with the selected row', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith({});
    expect(state.modalApi.open).toHaveBeenCalled();

    await actionButton(wrapper, 'common.edit').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);
  });

  it('opens probe and credential rotation for the selected row', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '探测').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);

    await actionButton(wrapper, '轮换凭据').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);
  });

  it('toggles endpoint status with the optimistic lock version', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '停用').trigger('click');
    await flushPromises();

    expect(updateModelEndpointStatus).toHaveBeenCalledWith(9, 3, false);
    expect(showSuccessMessage).toHaveBeenCalled();
    expect(state.gridApi.query).toHaveBeenCalled();
  });

  it('deletes the endpoint after confirmation', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();

    expect(deleteModelEndpoint).toHaveBeenCalledWith(9, 3);
    expect(state.gridApi.query).toHaveBeenCalled();
  });
});
