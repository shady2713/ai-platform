import type { SystemNotifyTemplateApi } from '#/api/system/notify/template';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteNotifyTemplate,
  getNotifyTemplatePage,
} from '#/api/system/notify/template';
import { showSuccessMessage } from '#/utils/feedback';

import NotifyTemplateIndex from './index.vue';

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
    setData: vi.fn(),
  },
  row: {} as SystemNotifyTemplateApi.Template,
}));

state.modalApi.setData.mockImplementation(() => state.modalApi);

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
        emits: ['success'],
        setup(_props, { emit }) {
          return () =>
            h(
              'button',
              { 'data-test': 'modal-success', onClick: () => emit('success') },
              '完成',
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

vi.mock('#/api/system/notify/template', () => ({
  deleteNotifyTemplate: vi.fn(),
  getNotifyTemplatePage: vi.fn(),
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
      name: 'NotifyTemplateFormStub',
      template: '<div />',
    }),
  };
});

function template(
  overrides: Partial<SystemNotifyTemplateApi.Template> = {},
): SystemNotifyTemplateApi.Template {
  return {
    code: 'password_reset',
    content: '您的验证码是 {code}',
    id: 3,
    name: '密码重置',
    nickname: '系统',
    params: ['code'],
    remark: '',
    status: 0,
    type: 1,
    ...overrides,
  };
}

function mountPage() {
  return mount(NotifyTemplateIndex, { attachTo: document.body });
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

describe('system notify template page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = template();
    state.gridConfig = undefined;
    state.modalApi.setData.mockImplementation(() => state.modalApi);
    vi.mocked(getNotifyTemplatePage).mockResolvedValue({
      list: [template()],
      total: 1,
    });
  });

  it('queries notify templates with paging and search values', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 2, pageSize: 10 } }, { name: 'x' });

    expect(getNotifyTemplatePage).toHaveBeenCalledWith({
      name: 'x',
      pageNo: 2,
      pageSize: 10,
    });
  });

  it('creates and edits a template through the form modal', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(null);
    expect(state.modalApi.open).toHaveBeenCalledOnce();

    await actionButton(wrapper, 'common.edit').trigger('click');
    expect(state.modalApi.setData).toHaveBeenLastCalledWith(state.row);
  });

  it('deletes a template after confirmation and refreshes the grid', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();

    expect(deleteNotifyTemplate).toHaveBeenCalledWith(3);
    expect(showSuccessMessage).toHaveBeenCalled();
    expect(state.gridApi.query).toHaveBeenCalledOnce();
    expect(state.closeLoading).toHaveBeenCalledOnce();
  });

  it('refreshes the grid when the form modal reports success', async () => {
    const wrapper = mountPage();

    await wrapper.find('[data-test="modal-success"]').trigger('click');
    await flushPromises();

    expect(state.gridApi.query).toHaveBeenCalledOnce();
  });
});
