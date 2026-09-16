import type { SystemSmsChannelApi } from '#/api/system/sms/channel';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { deleteSmsChannel, getSmsChannelPage } from '#/api/system/sms/channel';
import { showSuccessMessage } from '#/utils/feedback';

import SmsChannelIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

const state = vi.hoisted(() => ({
  closeLoading: vi.fn(),
  gridApi: {
    query: vi.fn(),
  },
  gridConfig: undefined as GridConfig | undefined,
  modalApi: {
    open: vi.fn(),
    setData: vi.fn(() => state.modalApi),
  },
  row: {} as SystemSmsChannelApi.Channel,
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

vi.mock('#/api/system/sms/channel', () => ({
  deleteSmsChannel: vi.fn(),
  getSmsChannelPage: vi.fn(),
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
      name: 'SmsChannelFormStub',
      template: '<div />',
    }),
  };
});

function channel(
  overrides: Partial<SystemSmsChannelApi.Channel> = {},
): SystemSmsChannelApi.Channel {
  return {
    callbackUrl: '',
    code: 'aliyun',
    id: 1,
    remark: '',
    signature: 'Basic Framework',
    status: 0,
    ...overrides,
  };
}

function mountPage() {
  return mount(SmsChannelIndex, { attachTo: document.body });
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

describe('system sms channel page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = channel();
    state.gridConfig = undefined;
    vi.mocked(getSmsChannelPage).mockResolvedValue({
      list: [channel()],
      total: 1,
    });
  });

  it('queries sms channels with paging and search values', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 1, pageSize: 10 } }, { name: 'x' });

    expect(getSmsChannelPage).toHaveBeenCalledWith({
      name: 'x',
      pageNo: 1,
      pageSize: 10,
    });
  });

  it('creates, edits and deletes an sms channel', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(null);
    expect(state.modalApi.open).toHaveBeenCalledOnce();

    await actionButton(wrapper, 'common.edit').trigger('click');
    expect(state.modalApi.setData).toHaveBeenLastCalledWith(state.row);

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();
    expect(deleteSmsChannel).toHaveBeenCalledWith(1);
    expect(showSuccessMessage).toHaveBeenCalled();
  });
});
