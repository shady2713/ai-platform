import type { SystemNotifyMessageApi } from '#/api/system/notify/message';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getNotifyMessagePage } from '#/api/system/notify/message';

import MessageIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

function message(): SystemNotifyMessageApi.Message {
  return {
    createTime: new Date('2026-09-01T00:00:00Z'),
    id: 6,
    readStatus: false,
    templateCode: 'password_reset',
    templateContent: '您的验证码是 {code}',
    templateId: 3,
    templateNickname: '系统',
    templateParams: { code: '123456' },
    templateType: 1,
    userId: 1,
    userType: 1,
  };
}

const state = vi.hoisted(() => ({
  gridApi: {
    query: vi.fn(),
  },
  gridConfig: undefined as GridConfig | undefined,
  modalApi: {
    open: vi.fn(),
    setData: vi.fn(() => state.modalApi),
  },
  modalConfigs: [] as Array<{ onOpenChange?: (open: boolean) => unknown }>,
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
    useVbenModal: vi.fn(
      (config: { onOpenChange?: (open: boolean) => unknown }) => {
        state.modalConfigs.push(config);
        return [
          defineComponent({
            name: 'ModalStub',
            emits: ['success'],
            setup(_props, { emit, slots }) {
              return () =>
                h('section', [
                  slots.default?.(),
                  h(
                    'button',
                    {
                      'data-test': 'modal-success',
                      onClick: () => emit('success'),
                    },
                    '完成',
                  ),
                ]);
            },
          }),
          state.modalApi,
        ];
      },
    ),
  };
});

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
    ACTION_ICON: { VIEW: 'lucide:eye' },
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
                slots.actions?.({ row: { id: 6, title: '站内信' } }),
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

vi.mock('#/api/system/notify/message', () => ({
  getNotifyMessagePage: vi.fn(),
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
    default: defineComponent({
      name: 'MessageDetailStub',
      template: '<div />',
    }),
  };
});

function mountPage() {
  return mount(MessageIndex, { attachTo: document.body });
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

describe('system notify message page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.gridConfig = undefined;
    state.modalConfigs = [];
    vi.mocked(getNotifyMessagePage).mockResolvedValue({
      list: [message()],
      total: 1,
    });
  });

  it('queries notify messages with paging and search values', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 2, pageSize: 20 } }, { title: 'x' });

    expect(getNotifyMessagePage).toHaveBeenCalledWith({
      pageNo: 2,
      pageSize: 20,
      title: 'x',
    });
  });

  it('opens the detail modal for the selected message and refreshes on success', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.detail').trigger('click');

    expect(state.modalApi.setData).toHaveBeenCalledWith({
      id: 6,
      title: '站内信',
    });
    expect(state.modalApi.open).toHaveBeenCalledOnce();
    expect(state.modalConfigs).toHaveLength(1);
  });

  it('refreshes the list when the detail modal reports success', async () => {
    const wrapper = mountPage();

    await wrapper.find('[data-test="modal-success"]').trigger('click');
    await flushPromises();

    expect(state.gridApi.query).toHaveBeenCalledOnce();
  });
});
