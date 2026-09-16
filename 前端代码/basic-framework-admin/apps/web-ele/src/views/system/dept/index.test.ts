import type { SystemDeptApi } from '#/api/system/dept';
import type { SystemUserApi } from '#/api/system/user';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { deleteDept, deleteDeptList, getDeptList } from '#/api/system/dept';
import { getSimpleUserList } from '#/api/system/user';
import { showWarningMessage } from '#/utils/feedback';

import DeptIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: () => unknown };
    };
  };
}

interface GridEvents {
  checkboxChange?: (params: { records: unknown[] }) => void;
}

const state = vi.hoisted(() => ({
  closeLoading: vi.fn(),
  gridApi: {
    grid: {
      clearCheckboxRow: vi.fn(),
      getCheckboxRecords: vi.fn<() => SystemDeptApi.Dept[]>(() => []),
      getTreeRowChildren: vi.fn<() => SystemDeptApi.Dept[]>(() => []),
      setAllTreeExpand: vi.fn(),
    },
    query: vi.fn(),
  },
  gridConfig: undefined as GridConfig | undefined,
  gridEvents: undefined as GridEvents | undefined,
  modalApi: {
    open: vi.fn(),
    setData: vi.fn(() => state.modalApi),
  },
  row: {} as SystemDeptApi.Dept,
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

vi.mock('@vben/utils', () => ({
  isEmpty: (value: unknown) =>
    Array.isArray(value) ? value.length === 0 : !value,
}));

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
      const options = gridOptions as GridConfig & {
        gridEvents?: GridEvents;
      };
      state.gridConfig = gridOptions as GridConfig;
      state.gridEvents = options.gridEvents;
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

vi.mock('#/api/system/dept', () => ({
  deleteDept: vi.fn(),
  deleteDeptList: vi.fn(),
  getDeptList: vi.fn(),
}));

vi.mock('#/api/system/user', () => ({
  getSimpleUserList: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showConfirmDialog: vi.fn(),
  showSuccessMessage: vi.fn(),
  showWarningMessage: vi.fn(),
}));

vi.mock('./data', () => ({
  attachDepartmentLeaderNames: vi.fn(
    (departments: unknown, users: unknown) => ({ departments, users }),
  ),
  useGridColumns: vi.fn(() => []),
}));

vi.mock('./modules/form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'DeptFormStub', template: '<div />' }),
  };
});

function department(
  overrides: Partial<SystemDeptApi.Dept> = {},
): SystemDeptApi.Dept {
  return {
    createTime: new Date('2026-09-01T00:00:00Z'),
    email: 'president@example.com',
    id: 1,
    leaderUserId: null,
    name: '总裁办',
    parentId: 0,
    phone: '',
    sort: 0,
    status: 0,
    ...overrides,
  };
}

function mountPage() {
  return mount(DeptIndex, { attachTo: document.body });
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

describe('system dept page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = department();
    state.gridConfig = undefined;
    state.gridEvents = undefined;
    vi.mocked(getDeptList).mockResolvedValue([department()]);
    vi.mocked(getSimpleUserList).mockResolvedValue([
      { id: 9, nickname: '张伟' } as SystemUserApi.User,
    ]);
    state.gridApi.grid.getTreeRowChildren.mockReturnValue([]);
    state.gridApi.grid.getCheckboxRecords.mockReturnValue([]);
  });

  it('loads departments and users and joins leader names for the tree', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.();

    expect(getDeptList).toHaveBeenCalledOnce();
    expect(getSimpleUserList).toHaveBeenCalledOnce();
  });

  it('toggles the tree expansion state', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '收缩').trigger('click');
    expect(state.gridApi.grid.setAllTreeExpand).toHaveBeenCalledWith(false);

    await actionButton(wrapper, '展开').trigger('click');
    expect(state.gridApi.grid.setAllTreeExpand).toHaveBeenLastCalledWith(true);
  });

  it('opens the create modal and the append modal for a row', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '新增下级').trigger('click');
    expect(state.modalApi.setData).toHaveBeenCalledWith({ parentId: 1 });
    expect(state.modalApi.open).toHaveBeenCalledOnce();
  });

  it('blocks deleting a department that still has children', async () => {
    state.gridApi.grid.getTreeRowChildren.mockReturnValue([
      department({ id: 2, name: '研发部' }),
    ]);
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();

    expect(showWarningMessage).toHaveBeenCalledWith('请先删除下级部门');
    expect(deleteDept).not.toHaveBeenCalled();
  });

  it('deletes leaf departments and batch deletes the checked rows', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();
    expect(deleteDept).toHaveBeenCalledWith(1);

    state.gridApi.grid.getCheckboxRecords.mockReturnValue([
      department({ id: 1 }),
      department({ id: 2 }),
    ]);
    state.gridEvents?.checkboxChange?.({ records: [] });
    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();
    expect(deleteDeptList).toHaveBeenCalledWith([1, 2]);
  });
});
