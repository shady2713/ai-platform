import type { AiGrantApi } from '#/api/ai/grant';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getGrantPage, revokeGrant } from '#/api/ai/grant';
import { showSuccessMessage } from '#/utils/feedback';

import AuthorizationIndex from './index.vue';

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
  row: {} as AiGrantApi.Grant,
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
        disabled: Boolean(action.disabled),
        onClick: handler,
      },
      String(action.label),
    );
  };
  return {
    ACTION_ICON: { ADD: 'lucide:plus', EDIT: 'lucide:edit' },
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

vi.mock('#/api/ai/grant', () => ({
  getGrantPage: vi.fn(),
  revokeGrant: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));
vi.mock('./data', () => ({
  PERMISSIONS: {
    create: 'ai:grant:create',
    query: 'ai:grant:query',
    revoke: 'ai:grant:revoke',
    update: 'ai:grant:update',
  },
  useGridColumns: vi.fn(() => []),
  useGridFormSchema: vi.fn(() => []),
}));
vi.mock('./modules/form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'GrantFormStub', template: '<div />' }),
  };
});

function grant(overrides: Partial<AiGrantApi.Grant> = {}): AiGrantApi.Grant {
  return {
    actions: ['READ'],
    applicationId: 5,
    authzRevision: 1,
    externalUserId: 'alice',
    id: 3,
    resourceKey: 'report-1',
    resourceType: 'REPORT',
    status: 'ACTIVE',
    subjectType: 'USER',
    version: 2,
    ...overrides,
  };
}

function queryGrant() {
  return (state.gridConfig as GridConfig)?.gridOptions.proxyConfig.ajax.query;
}

function mountPage() {
  return mount(AuthorizationIndex, { attachTo: document.body });
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

describe('ai authorization page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = grant();
    state.gridConfig = undefined;
    vi.mocked(getGrantPage).mockResolvedValue({ list: [grant()], total: 1 });
  });

  it('queries with paging and filters', async () => {
    mountPage();

    await queryGrant()?.(
      { page: { currentPage: 2, pageSize: 10 } },
      { externalUserId: 'alice', resourceType: 'REPORT' },
    );

    expect(getGrantPage).toHaveBeenCalledWith({
      externalUserId: 'alice',
      pageNo: 2,
      pageSize: 10,
      resourceType: 'REPORT',
    });
  });

  it('opens create and edit modals', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith({});

    await actionButton(wrapper, '修改动作').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith(state.row);
  });

  it('revokes authorization with the optimistic lock version and refreshes', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '撤销').trigger('click');
    await flushPromises();

    expect(revokeGrant).toHaveBeenCalledWith(3, 2);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      '授权已撤销，新请求与历史产物读取立即被拒绝',
    );
    expect(state.gridApi.query).toHaveBeenCalled();
  });

  it('disables edit and revoke for already revoked grants', async () => {
    state.row = grant({ status: 'REVOKED' });
    const wrapper = mountPage();

    expect(
      actionButton(wrapper, '修改动作').attributes('disabled'),
    ).toBeDefined();
    expect(actionButton(wrapper, '撤销').attributes('disabled')).toBeDefined();
  });
});
