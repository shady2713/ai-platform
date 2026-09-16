import type { SystemMenuApi } from '#/api/system/menu';
import type { SystemRoleApi } from '#/api/system/role';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getSimpleMenusList } from '#/api/system/menu';
import { assignRoleMenu, getRoleMenuList } from '#/api/system/permission';
import { showSuccessMessage } from '#/utils/feedback';

import AssignMenuForm from './assign-menu-form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

interface AssignMenuValues {
  id: number;
  menuIds: number[];
}

interface TreeNodeInput {
  index: number;
  value?: { type?: number };
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<AssignMenuValues>>(),
    setFieldValue: vi.fn(),
    setValues: vi.fn(() => Promise.resolve()),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  handleTree: vi.fn(),
  modalApi: {
    close: vi.fn(() => Promise.resolve()),
    getData: vi.fn(),
    lock: vi.fn(),
    unlock: vi.fn(),
  },
  modalConfig: undefined as ModalConfig | undefined,
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Tree: defineComponent({
      name: 'TreeStub',
      inheritAttrs: false,
      props: {
        defaultExpandedKeys: { default: () => [], type: Array },
        getNodeClass: { default: undefined, type: Function },
        treeData: { default: () => [], type: Array },
      },
      setup(props) {
        return () =>
          h('div', {
            'data-test': 'menu-tree',
            'data-tree-size': (props.treeData as unknown[]).length,
          });
      },
    }),
    useVbenModal: vi.fn((config: ModalConfig) => {
      state.modalConfig = config;
      return [
        defineComponent({
          name: 'ModalStub',
          setup(_props, { slots }) {
            return () =>
              h('section', [slots.default?.(), slots['prepend-footer']?.()]);
          },
        }),
        state.modalApi,
      ];
    }),
  };
});

vi.mock('@vben/constants', () => ({
  SystemMenuTypeEnum: { BUTTON: 3, DIR: 1, MENU: 2 },
}));

vi.mock('@vben/utils', () => ({ handleTree: state.handleTree }));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    ElCheckbox: defineComponent({
      name: 'ElCheckbox',
      props: { modelValue: Boolean },
      emits: ['change'],
      setup(_props, { emit, slots }) {
        return () =>
          h('button', { onClick: () => emit('change') }, slots.default?.());
      },
    }),
  };
});

vi.mock('#/adapter/form', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenForm: vi.fn(() => [
      defineComponent({
        name: 'FormStub',
        setup(_props, { slots }) {
          return () => h('form', slots.menuIds?.({}));
        },
      }),
      state.formApi,
    ]),
  };
});

vi.mock('#/api/system/menu', () => ({ getSimpleMenusList: vi.fn() }));
vi.mock('#/api/system/permission', () => ({
  assignRoleMenu: vi.fn(),
  getRoleMenuList: vi.fn(),
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));
vi.mock('../data', () => ({
  useAssignMenuFormSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('菜单权限弹窗配置未初始化');
  }
  return state.modalConfig;
}

function mountForm() {
  return mount(AssignMenuForm, {
    global: { directives: { loading: () => undefined } },
  });
}

function menu(
  id: number,
  name: string,
  overrides: Partial<SystemMenuApi.Menu> = {},
): SystemMenuApi.Menu {
  return {
    component: '',
    createTime: new Date('2026-01-01T00:00:00Z'),
    icon: '',
    id,
    keepAlive: false,
    name,
    parentId: 0,
    path: `/${name}`,
    permission: '',
    sort: id,
    status: 0,
    type: 2,
    visible: true,
    ...overrides,
  };
}

function role(overrides: Partial<SystemRoleApi.Role> = {}): SystemRoleApi.Role {
  return {
    code: 'auditor',
    dataScope: 1,
    dataScopeDeptIds: [],
    id: 9,
    name: '审计员',
    sort: 1,
    status: 0,
    type: 2,
    ...overrides,
  };
}

function checkbox(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper
    .findAllComponents({ name: 'ElCheckbox' })
    .find((item) => item.text() === label);
  if (!found) {
    throw new Error(`未找到复选框：${label}`);
  }
  return found;
}

describe('assign menu form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue({ id: 9, menuIds: [1, 2] });
    state.modalApi.getData.mockReturnValue(role());
    vi.mocked(getSimpleMenusList).mockResolvedValue([]);
    state.handleTree.mockReturnValue([]);
    vi.mocked(getRoleMenuList).mockResolvedValue([1, 2]);
    vi.mocked(assignRoleMenu).mockResolvedValue(true);
  });

  it('打开时加载菜单树并回填角色已有菜单，且始终解除锁定', async () => {
    const menus = [menu(1, '系统管理')];
    const tree = [{ children: [{ id: 2, name: '用户管理' }], id: 1 }];
    vi.mocked(getSimpleMenusList).mockResolvedValue(menus);
    state.handleTree.mockReturnValue(tree);
    mountForm();

    await modalConfig().onOpenChange(true);

    expect(getSimpleMenusList).toHaveBeenCalledOnce();
    expect(state.handleTree).toHaveBeenCalledWith(menus);
    expect(getRoleMenuList).toHaveBeenCalledWith(9);
    expect(state.formApi.setFieldValue).toHaveBeenCalledWith('menuIds', [1, 2]);
    expect(state.formApi.setValues).toHaveBeenCalledWith(role());
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('关闭事件直接返回，缺少角色编号时只加载菜单树', async () => {
    mountForm();

    await modalConfig().onOpenChange(false);
    expect(getSimpleMenusList).not.toHaveBeenCalled();

    state.modalApi.getData.mockReturnValue({});
    await modalConfig().onOpenChange(true);
    expect(getSimpleMenusList).toHaveBeenCalledOnce();
    expect(getRoleMenuList).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('校验失败时不锁定也不提交', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(state.formApi.getValues).not.toHaveBeenCalled();
    expect(assignRoleMenu).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('提交角色菜单并在成功后关闭、通知和解锁', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(assignRoleMenu).toHaveBeenCalledWith({ menuIds: [1, 2], roleId: 9 });
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('提交失败时保持弹窗打开并解除锁定', async () => {
    vi.mocked(assignRoleMenu).mockRejectedValue(new Error('request failed'));
    mountForm();

    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('树形控制支持递归全选、清空、展开与折叠', async () => {
    const tree = [
      { children: [{ id: 2 }, { children: [{ id: 4 }], id: 3 }], id: 1 },
      { name: '无编号节点' },
    ];
    state.handleTree.mockReturnValue(tree);
    vi.mocked(getSimpleMenusList).mockResolvedValue([menu(1, '系统管理')]);
    const wrapper = mountForm();
    await modalConfig().onOpenChange(true);
    await flushPromises();

    await checkbox(wrapper, '全选').trigger('click');
    expect(state.formApi.setFieldValue).toHaveBeenLastCalledWith(
      'menuIds',
      [1, 2, 3, 4],
    );
    await checkbox(wrapper, '全选').trigger('click');
    expect(state.formApi.setFieldValue).toHaveBeenLastCalledWith('menuIds', []);

    await checkbox(wrapper, '全部展开').trigger('click');
    expect(
      wrapper.findComponent({ name: 'TreeStub' }).props('defaultExpandedKeys'),
    ).toEqual([1, 2, 3, 4]);
    await checkbox(wrapper, '全部展开').trigger('click');
    expect(
      wrapper.findComponent({ name: 'TreeStub' }).props('defaultExpandedKeys'),
    ).toEqual([]);
  });

  it('按节点类型与序号计算树节点样式', async () => {
    const wrapper = mountForm();

    const tree = wrapper.findComponent({ name: 'TreeStub' });
    const getNodeClass = tree.props('getNodeClass') as (
      node: TreeNodeInput,
    ) => string;

    expect(getNodeClass({ index: 0, value: { type: 3 } })).toBe('inline-flex');
    expect(getNodeClass({ index: 1, value: { type: 3 } })).toBe(
      'inline-flex !pl-0',
    );
    expect(getNodeClass({ index: 2, value: { type: 1 } })).toBe('');
    expect(getNodeClass({ index: 0, value: undefined })).toBe('');
  });
});
