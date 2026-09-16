import type { SystemDeptApi } from '#/api/system/dept';
import type { SystemRoleApi } from '#/api/system/role';

import { flushPromises, mount } from '@vue/test-utils';

import { SystemDataScopeEnum } from '@vben/constants';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getDeptList } from '#/api/system/dept';
import { assignRoleDataScope } from '#/api/system/permission';
import { getRole } from '#/api/system/role';
import { showSuccessMessage } from '#/utils/feedback';

import AssignDataPermissionForm from './assign-data-permission-form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

interface FormValues {
  dataScope: number;
  dataScopeDeptIds: number[];
  id: number;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<FormValues>>(),
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
        checkStrictly: Boolean,
        defaultExpandedKeys: { default: () => [], type: Array },
        treeData: { default: () => [], type: Array },
      },
      setup(props) {
        return () => h('div', { 'data-check-strictly': props.checkStrictly });
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

vi.mock('#/adapter/form', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenForm: vi.fn(() => [
      defineComponent({
        name: 'FormStub',
        setup(_props, { slots }) {
          return () => h('form', slots.dataScopeDeptIds?.({}));
        },
      }),
      state.formApi,
    ]),
  };
});

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

vi.mock('#/api/system/dept', () => ({ getDeptList: vi.fn() }));
vi.mock('#/api/system/permission', () => ({
  assignRoleDataScope: vi.fn(),
}));
vi.mock('#/api/system/role', () => ({ getRole: vi.fn() }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));
vi.mock('../data', () => ({
  useAssignDataPermissionFormSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('数据权限弹窗配置未初始化');
  }
  return state.modalConfig;
}

function mountForm() {
  return mount(AssignDataPermissionForm, {
    global: { directives: { loading: () => undefined } },
  });
}

function department(
  id: number,
  name: string,
  children?: SystemDeptApi.Dept[],
): SystemDeptApi.Dept {
  return {
    children,
    createTime: new Date('2026-01-01T00:00:00Z'),
    email: '',
    id,
    leaderUserId: null,
    name,
    phone: '',
    sort: id,
    status: 0,
  };
}

function role(overrides: Partial<SystemRoleApi.Role> = {}): SystemRoleApi.Role {
  return {
    code: 'auditor',
    dataScope: SystemDataScopeEnum.DEPT_CUSTOM,
    dataScopeDeptIds: [2, 3],
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

describe('assign data permission form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue({
      dataScope: SystemDataScopeEnum.DEPT_CUSTOM,
      dataScopeDeptIds: [2, 3],
      id: 9,
    });
    state.modalApi.getData.mockReturnValue({ id: 9 });
    vi.mocked(getDeptList).mockResolvedValue([]);
    state.handleTree.mockReturnValue([]);
    vi.mocked(getRole).mockResolvedValue(role());
    vi.mocked(assignRoleDataScope).mockResolvedValue(true);
  });

  it('打开时先加载部门树再写入角色值，并始终解除锁定', async () => {
    const departments = [department(1, '总部')];
    const tree = [{ children: [{ id: 2, name: '研发部' }], id: 1 }];
    vi.mocked(getDeptList).mockResolvedValue(departments);
    state.handleTree.mockReturnValue(tree);
    const roleData = role();
    vi.mocked(getRole).mockResolvedValue(roleData);
    mountForm();

    await modalConfig().onOpenChange(true);

    expect(getDeptList).toHaveBeenCalledOnce();
    expect(state.handleTree).toHaveBeenCalledWith(departments);
    expect(getRole).toHaveBeenCalledWith(9);
    expect(state.formApi.setValues).toHaveBeenCalledWith(roleData);
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('关闭事件和缺少角色编号时不加载权限数据', async () => {
    mountForm();
    await modalConfig().onOpenChange(false);
    state.modalApi.getData.mockReturnValue({});
    await modalConfig().onOpenChange(true);

    expect(getDeptList).not.toHaveBeenCalled();
    expect(getRole).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('校验失败时不锁定也不提交', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(state.formApi.getValues).not.toHaveBeenCalled();
    expect(assignRoleDataScope).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('只为自定义范围提交部门，并在成功后关闭、通知和解锁', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(assignRoleDataScope).toHaveBeenCalledWith({
      dataScope: SystemDataScopeEnum.DEPT_CUSTOM,
      dataScopeDeptIds: [2, 3],
      roleId: 9,
    });
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();

    state.formApi.getValues.mockResolvedValue({
      dataScope: SystemDataScopeEnum.ALL,
      dataScopeDeptIds: [2, 3],
      id: 9,
    });
    await modalConfig().onConfirm();
    expect(assignRoleDataScope).toHaveBeenLastCalledWith({
      dataScope: SystemDataScopeEnum.ALL,
      dataScopeDeptIds: [],
      roleId: 9,
    });
  });

  it('提交失败时保持弹窗打开并解除锁定', async () => {
    vi.mocked(assignRoleDataScope).mockRejectedValue(
      new Error('request failed'),
    );
    mountForm();

    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('树形控制支持递归全选、清空、展开和切换父子联动', async () => {
    const tree = [
      { children: [{ id: 2 }, { children: [{ id: 4 }], id: 3 }], id: 1 },
      { name: '无编号节点' },
    ];
    state.handleTree.mockReturnValue(tree);
    vi.mocked(getDeptList).mockResolvedValue([
      department(1, '总部', [
        department(2, '研发一部'),
        department(3, '研发二部', [department(4, '平台组')]),
      ]),
    ]);
    const wrapper = mountForm();
    await modalConfig().onOpenChange(true);
    await flushPromises();

    await checkbox(wrapper, '全选').trigger('click');
    expect(state.formApi.setFieldValue).toHaveBeenLastCalledWith(
      'dataScopeDeptIds',
      [1, 2, 3, 4],
    );
    await checkbox(wrapper, '全选').trigger('click');
    expect(state.formApi.setFieldValue).toHaveBeenLastCalledWith(
      'dataScopeDeptIds',
      [],
    );

    await checkbox(wrapper, '全部展开').trigger('click');
    expect(
      wrapper.findComponent({ name: 'TreeStub' }).props('defaultExpandedKeys'),
    ).toEqual([1, 2, 3, 4]);
    await checkbox(wrapper, '父子联动').trigger('click');
    expect(
      wrapper.findComponent({ name: 'TreeStub' }).props('checkStrictly'),
    ).toBe(true);
  });
});
