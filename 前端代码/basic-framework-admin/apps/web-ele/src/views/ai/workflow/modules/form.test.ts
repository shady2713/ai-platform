import type { ModalConfig } from './setup';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createWorkflow, updateWorkflow } from '#/api/ai/workflow';

import Form from './form.vue';
import { createHarness, modalTitle, workflow } from './setup';

/**
 * 流程定义表单（`form.vue`）的用例。
 *
 * <p>拆分自已超行数上限的 `modules.test.ts`：mock 与 `vi.hoisted` 的 state 必须留在本文件，
 * 且要在被测组件被 import 之前生效；共享的纯助手与夹具在 `./setup`。
 */

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(),
    resetForm: vi.fn(() => Promise.resolve()),
    setValues: vi.fn(() => Promise.resolve()),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  modalApi: { close: vi.fn(), getData: vi.fn(), setState: vi.fn() },
  modalConfigs: [] as ModalConfig[],
}));

vi.mock('@vben/common-ui', async () => {
  // data.ts 依赖 @vben/common-ui 导出的 z（表单项校验规则），只能部分 mock
  const actual =
    await vi.importActual<typeof import('@vben/common-ui')>('@vben/common-ui');
  const { defineComponent, h } = await import('vue');
  const ModalStub = defineComponent({
    name: 'ModalStub',
    props: { title: { type: String, default: '' } },
    // 标题是弹窗承载的视图契约（"版本管理 · 某流程"），必须真的渲染出来才能断言
    setup:
      (props, { slots }) =>
      () =>
        h('section', { 'data-test': 'modal' }, [
          h('h1', { 'data-test': 'modal-title' }, props.title),
          slots.default?.(),
        ]),
  });
  return {
    z: actual.z,
    useVbenModal: vi.fn((config: ModalConfig) => {
      state.modalConfigs.push(config);
      return [ModalStub, state.modalApi];
    }),
  };
});

vi.mock('#/adapter/form', async () => {
  const { defineComponent, h } = await import('vue');
  const FormStub = defineComponent({
    name: 'FormStub',
    setup: () => () => h('div', { 'data-test': 'form' }),
  });
  return { useVbenForm: vi.fn(() => [FormStub, state.formApi]) };
});

vi.mock('#/api/ai/workflow', () => ({
  acceptWorkflowRun: vi.fn(),
  createWorkflow: vi.fn(),
  createWorkflowDraft: vi.fn(),
  discardWorkflowDraft: vi.fn(),
  getOpenWorkflowDraft: vi.fn(),
  getWorkflowRunNodeList: vi.fn(),
  getWorkflowRunPage: vi.fn(),
  getWorkflowVersion: vi.fn(),
  getWorkflowVersionPage: vi.fn(),
  publishWorkflowVersion: vi.fn(),
  updateWorkflow: vi.fn(),
  updateWorkflowDraft: vi.fn(),
  updateWorkflowStatus: vi.fn(),
}));

// extractErrorMessage 必须保持真实实现：面板"如实显示稳定原因码"这条契约就落在它身上，
// 换成桩函数等于把要测的行为换成了自己写的假行为
vi.mock('#/utils/feedback', async () => {
  const actual =
    await vi.importActual<typeof import('#/utils/feedback')>(
      '#/utils/feedback',
    );
  return { ...actual, showSuccessMessage: vi.fn() };
});
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('@vben/utils', () => ({ cloneDeep: (value: unknown) => value }));

// 把依赖装置的助手绑到本文件的 state 上：mock 留在本文件，state 因此也是一文件一份
const { openPanel, requireModalConfig } = createHarness(state);

describe('ai workflow modules · 流程定义表单', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfigs.length = 0;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(undefined);
  });

  it('新增：只提交四个可写字段，不带 id 与乐观锁版本', async () => {
    state.formApi.getValues.mockResolvedValue({
      applicationId: 71,
      code: 'order-summary-flow',
      description: '每晚汇总',
      name: '订单摘要生成流程',
    });
    vi.mocked(createWorkflow).mockResolvedValue(81);
    const onSuccess = vi.fn();

    const wrapper = await openPanel(Form, undefined, { onSuccess });

    // 没有行数据就是新增：必须清表且不能走 setValues 回填
    expect(state.formApi.resetForm).toHaveBeenCalled();
    expect(state.formApi.setValues).not.toHaveBeenCalled();
    expect(modalTitle(wrapper)).toBe('新增流程定义');

    await requireModalConfig().onConfirm?.();

    expect(createWorkflow).toHaveBeenCalledWith({
      applicationId: 71,
      code: 'order-summary-flow',
      description: '每晚汇总',
      name: '订单摘要生成流程',
    });
    // 提交体里不能凭空长出 id/version：后端会把它当成"修改"而缺少乐观锁基线
    const payload = vi.mocked(createWorkflow).mock.calls.at(-1)?.[0] ?? {};
    const keys = Object.keys(payload).join(',');
    expect(keys).toBe('applicationId,code,description,name');
    expect(updateWorkflow).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalled();
    expect(onSuccess).toHaveBeenCalled();
  });

  it('新增：说明留空归一化成空串，不提交 undefined', async () => {
    state.formApi.getValues.mockResolvedValue({
      applicationId: 71,
      code: 'order-summary-flow',
      name: '订单摘要生成流程',
    });
    vi.mocked(createWorkflow).mockResolvedValue(81);
    await openPanel(Form, undefined);

    await requireModalConfig().onConfirm?.();

    expect(createWorkflow).toHaveBeenCalledWith({
      applicationId: 71,
      code: 'order-summary-flow',
      description: '',
      name: '订单摘要生成流程',
    });
  });

  it('编辑：按 setData 的行回填，提交时带上 id 与乐观锁版本', async () => {
    const row = workflow();
    state.formApi.getValues.mockResolvedValue({
      applicationId: 71,
      code: 'order-summary-flow',
      name: '订单摘要生成流程',
    });
    vi.mocked(updateWorkflow).mockResolvedValue(true);
    const onSuccess = vi.fn();

    const wrapper = await openPanel(Form, row, { onSuccess });

    expect(state.formApi.setValues).toHaveBeenCalledWith({ ...row });
    expect(state.formApi.resetForm).not.toHaveBeenCalled();
    expect(modalTitle(wrapper)).toBe('编辑流程定义');

    await requireModalConfig().onConfirm?.();

    expect(updateWorkflow).toHaveBeenCalledWith({
      applicationId: 71,
      code: 'order-summary-flow',
      description: '',
      id: 81,
      name: '订单摘要生成流程',
      version: 3,
    });
    expect(createWorkflow).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalled();
    expect(onSuccess).toHaveBeenCalled();
  });

  it('表单校验不通过时一个请求都不发，也不关弹窗', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    await openPanel(Form, undefined);
    await requireModalConfig().onConfirm?.();

    expect(createWorkflow).not.toHaveBeenCalled();
    expect(updateWorkflow).not.toHaveBeenCalled();
    expect(state.modalApi.close).not.toHaveBeenCalled();
  });
});
