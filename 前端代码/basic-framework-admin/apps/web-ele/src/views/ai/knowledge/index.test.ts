import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteKnowledgeBase,
  getKnowledgeBasePage,
  updateKnowledgeBaseStatus,
} from '#/api/ai/knowledge';
import { showSuccessMessage } from '#/utils/feedback';

import KnowledgeIndex from './index.vue';

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
  row: {} as Record<string, unknown>,
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenForm: vi.fn(() => [
      defineComponent({
        name: 'FormStub',
        setup(_props, { slots }) {
          return () => h('form', slots.default?.());
        },
      }),
      {
        getValues: vi.fn(() => Promise.resolve({})),
        resetForm: vi.fn(() => Promise.resolve()),
        setValues: vi.fn(() => Promise.resolve()),
        validate: vi.fn(() => Promise.resolve({ valid: false })),
      },
    ]),
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
  const tableAction = defineComponent({
    name: 'TableActionStub',
    props: {
      actions: { default: () => [], type: Array },
      dropDownActions: { default: () => [], type: Array },
    },
    setup(props, { slots }) {
      return () =>
        h('div', [
          ...(props.actions as ActionStub[]).map((action, index) =>
            h(
              'button',
              {
                'data-action': action.label,
                'data-auth': (action.auth ?? []).join(','),
                'data-testid': `action-${index}`,
                onClick: action.onClick,
              },
              action.label,
            ),
          ),
          ...(props.dropDownActions as ActionStub[]).map((action, index) =>
            h(
              'button',
              {
                'data-action': action.label,
                'data-auth': (action.auth ?? []).join(','),
                'data-testid': `dropdown-${index}`,
                onClick: action.popConfirm?.confirm ?? action.onClick,
              },
              action.label,
            ),
          ),
          slots.default?.(),
        ]);
    },
  });
  return {
    ACTION_ICON: {
      ADD: 'add',
      DELETE: 'delete',
      EDIT: 'edit',
      SEARCH: 'search',
      VIEW: 'view',
    },
    TableAction: tableAction,
    useVbenVxeGrid: vi.fn((config: GridConfig) => {
      state.gridConfig = config;
      return [
        defineComponent({
          name: 'GridStub',
          setup(_props, { slots }) {
            return () =>
              h('div', [
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

vi.mock('#/api/ai/knowledge', () => ({
  deleteKnowledgeBase: vi.fn(() => Promise.resolve(true)),
  getKnowledgeBasePage: vi.fn(() => Promise.resolve({ list: [], total: 0 })),
  updateKnowledgeBaseStatus: vi.fn(() => Promise.resolve(true)),
}));

vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));

vi.mock('#/locales', () => ({
  $t: (key: string, args?: unknown[]) =>
    `${key}${args ? `:${args.join(',')}` : ''}`,
}));

/** 测试替身里的动作形状（与 TableAction 的 actions 项一致的最小子集）。 */
interface ActionStub {
  auth?: string[];
  label?: string;
  onClick?: () => unknown;
  popConfirm?: { confirm?: () => unknown; title?: string };
}

describe('ai knowledge index page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = { id: 61, name: '员工手册', status: 'ENABLED', version: 3 };
  });

  it('查询走知识库分页接口并带上分页参数', async () => {
    mount(KnowledgeIndex);
    await flushPromises();
    const config = state.gridConfig as GridConfig;
    await config.gridOptions.proxyConfig.ajax.query(
      { page: { currentPage: 2, pageSize: 20 } },
      { name: 'handbook' },
    );
    expect(getKnowledgeBasePage).toHaveBeenCalledWith({
      name: 'handbook',
      pageNo: 2,
      pageSize: 20,
    });
  });

  it('写操作按钮带权限码（无管理权时不可见由框架按 auth 判定）', async () => {
    const wrapper = mount(KnowledgeIndex);
    await flushPromises();
    const actions = wrapper.findAll('[data-action]');
    const auths = actions.map((action) => action.attributes('data-auth'));
    expect(auths).toContain('ai:knowledge:create');
    expect(auths).toContain('ai:knowledge:delete');
    expect(auths).toContain('ai:knowledge:update');
    expect(auths).toContain('ai:knowledge:query');
    expect(auths).toContain('ai:knowledge:debug');
  });

  it('启停与删除调用后端并刷新列表', async () => {
    const wrapper = mount(KnowledgeIndex);
    await flushPromises();
    const toggle = wrapper
      .findAll('[data-action]')
      .find((action) => action.attributes('data-action') === '停用');
    await toggle?.trigger('click');
    await flushPromises();
    expect(updateKnowledgeBaseStatus).toHaveBeenCalledWith(61, 3, false);
    expect(showSuccessMessage).toHaveBeenCalled();

    const remove = wrapper
      .findAll('[data-action]')
      .find((action) => action.attributes('data-action')?.includes('delete'));
    await remove?.trigger('click');
    await flushPromises();
    expect(deleteKnowledgeBase).toHaveBeenCalledWith(61, 3);
    expect(state.gridApi.query).toHaveBeenCalled();
  });
});
