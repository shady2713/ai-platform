import type { SystemMenuApi } from '#/api/system/menu';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { deleteMenu, getMenuList } from '#/api/system/menu';

import MenuIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: () => unknown };
    };
  };
}

const state = vi.hoisted(() => ({
  closeLoading: vi.fn(),
  gridApi: {
    grid: { setAllTreeExpand: vi.fn() },
    query: vi.fn(),
  },
  gridConfig: undefined as GridConfig | undefined,
  modalApi: {
    open: vi.fn(),
    setData: vi.fn(() => state.modalApi),
  },
  row: {} as SystemMenuApi.Menu,
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

vi.mock('@vben/constants', () => ({
  SystemMenuTypeEnum: { BUTTON: 3, DIR: 1, MENU: 2 },
}));

vi.mock('@vben/icons', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    IconifyIcon: defineComponent({
      name: 'IconifyIcon',
      props: { icon: { type: String, default: '' } },
      setup(props) {
        return () =>
          h('span', { 'data-icon': props.icon, 'data-test': 'menu-icon' });
      },
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
                slots.name?.({ row: state.row }),
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

vi.mock('#/api/system/menu', () => ({
  deleteMenu: vi.fn(),
  getMenuList: vi.fn(),
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
}));

vi.mock('./modules/form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'MenuFormStub', template: '<div />' }),
  };
});

function menu(overrides: Partial<SystemMenuApi.Menu> = {}): SystemMenuApi.Menu {
  return {
    component: '',
    createTime: new Date('2026-09-01T00:00:00Z'),
    icon: 'lucide:user',
    id: 1,
    keepAlive: false,
    name: '用户管理',
    parentId: 0,
    path: '/system/user',
    permission: '',
    sort: 0,
    status: 0,
    type: 2,
    visible: true,
    ...overrides,
  };
}

function mountPage() {
  return mount(MenuIndex, { attachTo: document.body });
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

describe('system menu page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = menu();
    state.gridConfig = undefined;
    vi.mocked(getMenuList).mockResolvedValue([menu()]);
  });

  it('loads the full menu list without paging', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.();

    expect(getMenuList).toHaveBeenCalledOnce();
  });

  it('renders the menu icon or the button marker for each row', () => {
    state.row = menu({ type: 3 });
    const buttonWrapper = mountPage();
    expect(
      buttonWrapper.find('[data-test="menu-icon"]').attributes('data-icon'),
    ).toBe('carbon:square-outline');

    state.row = menu({ type: 2 });
    const menuWrapper = mountPage();
    expect(
      menuWrapper.find('[data-test="menu-icon"]').attributes('data-icon'),
    ).toBe('lucide:user');
  });

  it('toggles the tree expansion state', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '展开').trigger('click');
    expect(state.gridApi.grid.setAllTreeExpand).toHaveBeenCalledWith(true);

    await actionButton(wrapper, '收缩').trigger('click');
    expect(state.gridApi.grid.setAllTreeExpand).toHaveBeenLastCalledWith(false);
  });

  it('appends a sub menu and deletes a menu after confirmation', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '新增下级').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith({ parentId: 1 });
    expect(state.modalApi.open).toHaveBeenCalledOnce();

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();
    expect(deleteMenu).toHaveBeenCalledWith(1);
  });
});
