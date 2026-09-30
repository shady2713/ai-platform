import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createMasterObject,
  getMasterObjectPage,
  updateMasterObject,
  updateMasterObjectStatus,
} from '#/api/ai/semantic';
import { showRequestError, showSuccessMessage } from '#/utils/feedback';

import SemanticIndex from './index.vue';

interface GridConfig {
  gridOptions: {
    proxyConfig: {
      ajax: { query: (params: unknown, formValues: unknown) => unknown };
    };
  };
}

const state = vi.hoisted(() => ({
  access: { hasAccessByCodes: vi.fn<(codes: string[]) => boolean>() },
  createFormApi: {
    getValues: vi.fn(),
    resetForm: vi.fn(),
    setValues: vi.fn(),
    validate: vi.fn(),
  },
  gridConfig: undefined as unknown,
  gridQuery: vi.fn(),
  formModalApi: {
    close: vi.fn(),
    getData: vi.fn(),
    lastData: undefined as Record<string, unknown> | undefined,
    onOpenChange: undefined as ((isOpen: boolean) => void) | undefined,
    lock: vi.fn(),
    onConfirm: undefined as (() => Promise<void>) | undefined,
    open: vi.fn(),
    setData: vi.fn(),
    setState: vi.fn(),
    unlock: vi.fn(),
  },
  moduleModalApi: { open: vi.fn(), setData: vi.fn() },
  row: {} as Record<string, unknown>,
}));

state.formModalApi.setData = vi.fn(
  (data: Record<string, unknown> | undefined) => {
    state.formModalApi.lastData = data;
    return state.formModalApi;
  },
);
state.formModalApi.getData = vi.fn(() => state.formModalApi.lastData);
state.formModalApi.open = vi.fn(() => {
  state.formModalApi.onOpenChange?.(true);
  return state.formModalApi;
});
state.moduleModalApi.setData = vi.fn(() => state.moduleModalApi);
state.moduleModalApi.open = vi.fn(() => state.moduleModalApi);

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: state.access.hasAccessByCodes }),
}));

vi.mock('@vben/common-ui', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/common-ui')>('@vben/common-ui');
  const { defineComponent, h } = await import('vue');
  return {
    z: actual.z,
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', slots.default?.());
      },
    }),
    useVbenModal: vi.fn(
      (config?: {
        onConfirm?: () => Promise<void>;
        onOpenChange?: (isOpen: boolean) => void;
      }) => {
        const modal = defineComponent({
          name: 'ModalStub',
          setup(_props, { slots }) {
            return () => h('section', slots.default?.());
          },
        });
        if (config?.onConfirm) {
          state.formModalApi.onConfirm = config.onConfirm;
          state.formModalApi.onOpenChange = config.onOpenChange;
          return [modal, state.formModalApi];
        }
        return [modal, state.moduleModalApi];
      },
    ),
  };
});

vi.mock('#/adapter/form', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenForm: vi.fn(() => [
      defineComponent({
        name: 'FormStub',
        setup(_props, { slots }) {
          return () => h('form', slots.default?.());
        },
      }),
      state.createFormApi,
    ]),
  };
});

vi.mock('#/adapter/vxe-table', async () => {
  const { defineComponent, h } = await import('vue');
  const renderAction = (action: Record<string, unknown>) =>
    h(
      'button',
      { 'data-action': String(action.label), onClick: action.onClick as never },
      String(action.label),
    );
  return {
    ACTION_ICON: { ADD: 'lucide:plus' },
    TableAction: defineComponent({
      name: 'TableAction',
      props: {
        actions: { type: Array, default: () => [] },
        drops: { type: Array, default: () => [] },
      },
      setup(props) {
        return () =>
          h('div', [
            ...(props.actions as Record<string, unknown>[]).map((action) =>
              renderAction(action),
            ),
            ...(props.drops as Record<string, unknown>[]).map((action) =>
              renderAction(action),
            ),
          ]);
      },
    }),
    useVbenVxeGrid: vi.fn((gridConfig: unknown) => {
      state.gridConfig = gridConfig;
      return [
        defineComponent({
          name: 'GridStub',
          setup(_props, { slots }) {
            return () =>
              h('div', { 'data-test': 'grid' }, [
                slots['toolbar-tools']?.(),
                slots.action?.({ row: state.row }),
              ]);
          },
        }),
        { query: state.gridQuery },
      ];
    }),
  };
});

vi.mock('#/api/ai/semantic', () => ({
  createMasterObject: vi.fn(),
  getMasterObjectPage: vi.fn(),
  updateMasterObject: vi.fn(),
  updateMasterObjectStatus: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showRequestError: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

function masterObject(): Record<string, unknown> {
  return {
    currentRevision: 2,
    description: '',
    id: 5,
    objectCode: 'md_cloud_qi',
    objectName: '云启科技（统一客户）',
    objectType: 'CUSTOMER',
    status: 'ACTIVE',
    version: 3,
  };
}

async function render() {
  const wrapper = mount(SemanticIndex);
  await flushPromises();
  return wrapper;
}

describe('主数据映射页面（Y02）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.access.hasAccessByCodes.mockReturnValue(true);
    state.row = masterObject();
    state.createFormApi.validate.mockResolvedValue({ valid: true });
    state.createFormApi.getValues.mockResolvedValue({
      description: 'CRM/ERP 客户统一对象',
      objectCode: 'md_cloud_qi',
      objectName: '云启科技（统一客户）',
      objectType: 'CUSTOMER',
    });
    vi.mocked(createMasterObject).mockResolvedValue(5);
    vi.mocked(updateMasterObject).mockResolvedValue(true);
    vi.mocked(getMasterObjectPage).mockResolvedValue({
      list: [masterObject() as never],
      total: 1,
    });
    vi.mocked(updateMasterObjectStatus).mockResolvedValue(true);
  });

  it('新建对象提交对象事实并在成功后刷新列表', async () => {
    const wrapper = await render();

    await wrapper.get('[data-action="新建统一对象"]').trigger('click');
    await state.formModalApi.onConfirm?.();
    await flushPromises();

    expect(createMasterObject).toHaveBeenCalledWith({
      description: 'CRM/ERP 客户统一对象',
      objectCode: 'md_cloud_qi',
      objectName: '云启科技（统一客户）',
      objectType: 'CUSTOMER',
    });
    expect(showSuccessMessage).toHaveBeenCalledWith(
      '对象已创建，请登记映射版本并发布',
    );
    expect(state.gridQuery).toHaveBeenCalled();
  });

  it('修改对象不允许改标识（不提交 objectCode），并携带乐观锁版本', async () => {
    state.createFormApi.getValues.mockResolvedValue({
      description: '',
      objectName: '云启科技 v2',
      objectType: 'CUSTOMER',
    });
    const wrapper = await render();

    await wrapper.get('[data-action="编辑"]').trigger('click');
    await state.formModalApi.onConfirm?.();
    await flushPromises();

    expect(updateMasterObject).toHaveBeenCalledWith({
      description: '',
      id: 5,
      objectName: '云启科技 v2',
      objectType: 'CUSTOMER',
      version: 3,
    });
    expect(updateMasterObject).not.toHaveBeenCalledWith(
      expect.objectContaining({ objectCode: expect.anything() }),
    );
  });

  it('保存失败按错误码提示且不刷新列表', async () => {
    vi.mocked(createMasterObject).mockRejectedValueOnce(new Error('duplicate'));
    const wrapper = await render();
    state.gridQuery.mockClear();

    await wrapper.get('[data-action="新建统一对象"]').trigger('click');
    await state.formModalApi.onConfirm?.();
    await flushPromises();

    expect(showRequestError).toHaveBeenCalledWith(
      expect.any(Error),
      '对象保存失败',
    );
    expect(state.gridQuery).not.toHaveBeenCalled();
  });

  it('版本与映射 / 判定与目录入口把当前对象传给对应弹窗', async () => {
    const wrapper = await render();

    await wrapper.get('[data-action="版本与映射"]').trigger('click');
    await wrapper.get('[data-action="判定与目录"]').trigger('click');
    await flushPromises();

    expect(state.moduleModalApi.setData).toHaveBeenCalledWith(
      expect.objectContaining({ objectCode: 'md_cloud_qi' }),
    );
    expect(state.moduleModalApi.open).toHaveBeenCalledTimes(2);
  });

  it('列表查询只提交查询表单事实与分页', async () => {
    await render();
    const config = state.gridConfig as GridConfig;

    await config.gridOptions.proxyConfig.ajax.query(
      { page: { currentPage: 2, pageSize: 50 } },
      { keyword: 'md', status: 'ACTIVE' },
    );

    expect(getMasterObjectPage).toHaveBeenCalledWith({
      keyword: 'md',
      pageNo: 2,
      pageSize: 50,
      status: 'ACTIVE',
    });
  });

  it('维护入口按 ai:semantic:manage 显示，无维护权时只剩只读入口', async () => {
    state.access.hasAccessByCodes.mockReturnValue(false);
    const wrapper = await render();
    const text = wrapper.text();

    expect(text).not.toContain('新建统一对象');
    expect(text).toContain('版本与映射');
    expect(text).toContain('判定与目录');
  });

  it('启停提交乐观锁版本并提示阻断语义', async () => {
    const wrapper = await render();

    await wrapper.get('[data-action="停用"]').trigger('click');
    await flushPromises();

    expect(updateMasterObjectStatus).toHaveBeenCalledWith(5, 3, false);
    expect(showSuccessMessage).toHaveBeenCalledWith('已停用：判定将阻断');
    expect(state.gridQuery).toHaveBeenCalled();
  });

  it('页面常驻展示"不按同名合并/冲突阻断"的语义提示', async () => {
    const wrapper = await render();
    const text = wrapper.text();

    expect(text).toContain('同名不会合并');
    expect(text).toContain('不会替你挑一个');
  });
});
