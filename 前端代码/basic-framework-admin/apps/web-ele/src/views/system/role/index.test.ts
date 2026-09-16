import type { SystemRoleApi } from '#/api/system/role';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteRole,
  deleteRoleList,
  exportRole,
  getRolePage,
} from '#/api/system/role';
import { showConfirmDialog } from '#/utils/feedback';

import RoleIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

interface GridEvents {
  checkboxChange?: (params: { records: unknown[] }) => void;
  proxyQuery?: () => void;
}

interface ModalApiStub {
  open: ReturnType<typeof vi.fn>;
  setData: ReturnType<typeof vi.fn>;
}

const state = vi.hoisted(() => ({
  closeLoading: vi.fn(),
  downloadFileFromBlobPart: vi.fn(),
  gridApi: {
    formApi: { getValues: vi.fn() },
    query: vi.fn(),
  },
  gridConfig: undefined as GridConfig | undefined,
  gridEvents: undefined as GridEvents | undefined,
  modalApis: [] as ModalApiStub[],
  row: {} as SystemRoleApi.Role,
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
    useVbenModal: vi.fn(() => {
      const api: ModalApiStub = {
        open: vi.fn(),
        setData: vi.fn(() => api),
      };
      state.modalApis.push(api);
      return [
        defineComponent({
          name: 'ModalStub',
          emits: ['success'],
          setup(_props, { emit }) {
            return () =>
              h(
                'button',
                {
                  'data-test': 'modal-success',
                  onClick: () => emit('success'),
                },
                '完成',
              );
          },
        }),
        api,
      ];
    }),
  };
});

vi.mock('@vben/utils', () => ({
  downloadFileFromBlobPart: state.downloadFileFromBlobPart,
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
      DOWNLOAD: 'lucide:download',
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
      state.gridConfig = options;
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

vi.mock('#/api/system/role', () => ({
  deleteRole: vi.fn(),
  deleteRoleList: vi.fn(),
  exportRole: vi.fn(),
  getRolePage: vi.fn(),
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
    default: defineComponent({ name: 'RoleFormStub', template: '<div />' }),
  };
});

vi.mock('./modules/assign-menu-form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({
      name: 'AssignMenuFormStub',
      template: '<div />',
    }),
  };
});

vi.mock('./modules/assign-data-permission-form.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({
      name: 'AssignDataPermissionFormStub',
      template: '<div />',
    }),
  };
});

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

function mountPage() {
  return mount(RoleIndex, { attachTo: document.body });
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

function modalApi(index: number) {
  const api = state.modalApis[index];
  if (!api) {
    throw new Error(`弹窗未初始化：${index}`);
  }
  return api;
}

describe('system role page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.row = role();
    state.gridConfig = undefined;
    state.gridEvents = undefined;
    state.modalApis = [];
    vi.mocked(getRolePage).mockResolvedValue({ list: [role()], total: 1 });
    state.gridApi.formApi.getValues.mockResolvedValue({ name: '审计' });
    vi.mocked(exportRole).mockResolvedValue(new Blob(['xls']));
  });

  it('queries roles with paging and search values', async () => {
    mountPage();
    const query = state.gridConfig?.gridOptions.proxyConfig.ajax.query;

    await query?.({ page: { currentPage: 2, pageSize: 20 } }, { name: 'x' });

    expect(getRolePage).toHaveBeenCalledWith({
      name: 'x',
      pageNo: 2,
      pageSize: 20,
    });
  });

  it('creates and edits a role through the form modal', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.create').trigger('click');
    expect(modalApi(0).setData).toHaveBeenCalledWith(null);
    expect(modalApi(0).open).toHaveBeenCalledOnce();

    await actionButton(wrapper, 'common.edit').trigger('click');
    expect(modalApi(0).setData).toHaveBeenLastCalledWith(state.row);
  });

  it('exports the search values as an xls download', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'ui.actionTitle.export').trigger('click');
    await flushPromises();

    expect(exportRole).toHaveBeenCalledWith({ name: '审计' });
    expect(state.downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: '角色.xls',
      source: expect.any(Blob),
    });
  });

  it('deletes a role after confirmation and refreshes the grid', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, 'common.delete').trigger('click');
    await flushPromises();

    expect(deleteRole).toHaveBeenCalledWith(9);
    expect(state.gridApi.query).toHaveBeenCalledOnce();
    expect(state.closeLoading).toHaveBeenCalledOnce();
  });

  it('batch deletes the checked roles and skips when cancelled', async () => {
    const wrapper = mountPage();

    state.gridEvents?.checkboxChange?.({
      records: [role({ id: 9 }), role({ id: 10 })],
    });
    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();
    expect(deleteRoleList).toHaveBeenCalledWith([9, 10]);

    vi.mocked(showConfirmDialog).mockRejectedValueOnce(new Error('cancel'));
    state.gridEvents?.checkboxChange?.({ records: [role({ id: 11 })] });
    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();
    expect(deleteRoleList).toHaveBeenCalledTimes(1);
  });

  it('clears the checked ids before a new proxy query', async () => {
    const wrapper = mountPage();

    state.gridEvents?.checkboxChange?.({ records: [role({ id: 9 })] });
    state.gridEvents?.proxyQuery?.();
    await actionButton(wrapper, 'ui.actionTitle.deleteBatch').trigger('click');
    await flushPromises();

    // proxyQuery 清空勾选后，批量删除提交的是空数组
    expect(deleteRoleList).toHaveBeenCalledWith([]);
  });

  it('opens the data permission and menu permission modals for a row', async () => {
    const wrapper = mountPage();

    await actionButton(wrapper, '数据权限').trigger('click');
    expect(modalApi(1).setData).toHaveBeenCalledWith(state.row);
    expect(modalApi(1).open).toHaveBeenCalledOnce();

    await actionButton(wrapper, '菜单权限').trigger('click');
    expect(modalApi(2).setData).toHaveBeenCalledWith(state.row);
    expect(modalApi(2).open).toHaveBeenCalledOnce();
  });

  it('refreshes the grid when a modal reports success', async () => {
    const wrapper = mountPage();

    await wrapper.find('[data-test="modal-success"]').trigger('click');
    await flushPromises();

    expect(state.gridApi.query).toHaveBeenCalledOnce();
  });
});
