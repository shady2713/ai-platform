import type { DOMWrapper } from '@vue/test-utils';

import type { ModalConfig } from './setup';

import { flushPromises } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  acceptWorkflowRun,
  getWorkflowRunNodeList,
  getWorkflowRunPage,
} from '#/api/ai/workflow';
import { showSuccessMessage } from '#/utils/feedback';

import Runs from './runs.vue';
import {
  button,
  createHarness,
  modalTitle,
  row,
  rowAt,
  rowsOf,
  run,
  runPanelData,
} from './setup';

/**
 * 运行记录面板（`runs.vue`）的用例。
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
const { openPanel } = createHarness(state);

describe('ai workflow modules · 运行记录面板', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfigs.length = 0;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(runPanelData());
    vi.mocked(getWorkflowRunPage).mockResolvedValue({ list: [], total: 0 });
    vi.mocked(getWorkflowRunNodeList).mockResolvedValue([]);
  });

  it('受理模式：打开即拉第一页，并渲染受理表单', async () => {
    const wrapper = await openPanel(Runs, runPanelData());
    expect(getWorkflowRunPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      status: undefined,
      workflowId: 81,
    });
    expect(state.formApi.resetForm).toHaveBeenCalled();
    expect(modalTitle(wrapper)).toBe('运行记录 · 订单摘要生成流程');
    expect(wrapper.findAll('[data-test="form"]')).toHaveLength(1);
    expect(wrapper.text()).toContain(
      '还没有运行记录；流程停用或没有已发布版本时不会受理新运行。',
    );
  });

  it('只读模式：不渲染受理表单', async () => {
    const wrapper = await openPanel(Runs, runPanelData(false));
    expect(wrapper.findAll('[data-test="form"]')).toHaveLength(0);
    expect(
      wrapper
        .findAll('button')
        .filter((b: DOMWrapper<Element>) => b.text() === '受理并执行'),
    ).toHaveLength(0);
  });

  it('没有流程上下文时不发请求', async () => {
    const wrapper = await openPanel(Runs, undefined);
    expect(getWorkflowRunPage).not.toHaveBeenCalled();
    expect(modalTitle(wrapper)).toBe('运行记录');
  });

  it('受理：把表单值按契约提交，并直接用响应里的节点事实', async () => {
    state.formApi.getValues.mockResolvedValue({
      dataLevel: 'L2_INTERNAL',
      idempotencyKey: 'idem-key-0000000001',
      inputText: '订单 1001',
      maxDurationMillis: 30_000,
      maxSteps: 6,
    });
    vi.mocked(acceptWorkflowRun).mockResolvedValue({
      id: 301,
      nodeExecuted: 2,
      nodeTotal: 2,
      nodes: [
        { nodeKey: 'a', nodeType: 'START', status: 'SUCCEEDED' },
        { nodeKey: 'b', nodeType: 'END', status: 'SUCCEEDED' },
      ],
      status: 'SUCCEEDED',
      workflowId: 81,
    });
    const wrapper = await openPanel(Runs, runPanelData());

    await button(wrapper, '受理并执行').trigger('click');
    await flushPromises();

    expect(acceptWorkflowRun).toHaveBeenCalledWith({
      dataLevel: 'L2_INTERNAL',
      idempotencyKey: 'idem-key-0000000001',
      inputText: '订单 1001',
      maxDurationMillis: 30_000,
      maxSteps: 6,
      workflowId: 81,
    });
    expect(showSuccessMessage).toHaveBeenCalledWith('成功');
    // 受理响应自带节点事实，不该再打一次 node-list
    expect(getWorkflowRunNodeList).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('节点留痕 · 运行 #301（按执行顺序）');
    // 节点类型按冻结白名单映射成中文名，不把内部枚举直接甩给用户
    const acceptNodeRows = rowsOf(wrapper, 1);
    expect(row(acceptNodeRows, 0).text()).toContain('a开始成功');
    expect(row(acceptNodeRows, 1).text()).toContain('b结束成功');
  });

  it('受理：预算与输入留空时不出现在请求体里', async () => {
    state.formApi.getValues.mockResolvedValue({
      dataLevel: 'L2_INTERNAL',
      idempotencyKey: 'idem-key-0000000002',
    });
    vi.mocked(acceptWorkflowRun).mockResolvedValue({
      id: 302,
      status: 'SUCCEEDED',
      workflowId: 81,
    });
    const wrapper = await openPanel(Runs, runPanelData());

    await button(wrapper, '受理并执行').trigger('click');
    await flushPromises();

    // 走一遍 JSON 序列化：面板构造的对象里带 undefined 键，真正发出去的是序列化后的
    // 结果，所以留空的预算必须真的没带上。这里不能用 structuredClone：它保留 undefined 键，
    // 那样断言就变成在检查自己的假设而不是实际请求体。
    // eslint-disable-next-line unicorn/prefer-structured-clone
    const sent = JSON.parse(
      JSON.stringify(vi.mocked(acceptWorkflowRun).mock.calls.at(-1)?.[0] ?? {}),
    );
    expect(sent).toStrictEqual({
      dataLevel: 'L2_INTERNAL',
      idempotencyKey: 'idem-key-0000000002',
      workflowId: 81,
    });
    expect(acceptWorkflowRun).toHaveBeenCalledWith(
      expect.objectContaining({
        inputText: undefined,
        maxDurationMillis: undefined,
        maxSteps: undefined,
      }),
    );
  });

  it('受理：校验不通过不发请求；受理失败原样回显原因码', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    const wrapper = await openPanel(Runs, runPanelData());
    await button(wrapper, '受理并执行').trigger('click');
    await flushPromises();
    expect(acceptWorkflowRun).not.toHaveBeenCalled();

    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue({
      dataLevel: 'L2_INTERNAL',
      idempotencyKey: 'idem-key-0000000003',
    });
    vi.mocked(acceptWorkflowRun).mockRejectedValue({
      response: { data: { msg: 'AI_WORKFLOW_VERSION_NOT_PUBLISHED' } },
    });
    await button(wrapper, '受理并执行').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('AI_WORKFLOW_VERSION_NOT_PUBLISHED');
    expect(showSuccessMessage).not.toHaveBeenCalled();
    // 受理失败后仍刷新列表：失败原因可能已写入运行记录
    expect(getWorkflowRunPage).toHaveBeenCalledTimes(2);
  });

  it('运行列表：区分"需要人工确认"与"真正失败"两类措辞', async () => {
    vi.mocked(getWorkflowRunPage).mockResolvedValue({
      list: [
        run({
          currentNodeKey: 'tool',
          durationMs: 1500,
          errorCode: 'AI_TOOL_CONFIRMATION_REQUIRED',
          nodeExecuted: 2,
          nodeTotal: 3,
          status: 'FAILED',
        }),
        run({
          durationMs: 320,
          errorCode: 'AI_MODEL_CALL_FAILED',
          id: 302,
          nodeExecuted: 1,
          nodeTotal: 2,
          status: 'FAILED',
        }),
        run({ currentNodeKey: 'a', id: 303 }),
      ],
      total: 3,
    });
    const wrapper = await openPanel(Runs, runPanelData());

    const listRows = rowsOf(wrapper);
    expect(listRows).toHaveLength(3);
    const confirmText = row(listRows, 0).text();
    expect(confirmText).toContain('#301');
    expect(confirmText).toContain('2/3');
    expect(confirmText).toContain('tool');
    expect(confirmText).toContain('1.50 秒');
    // 受控结束不是执行失败：措辞必须说清楚，且不得直接把裸码当结论
    expect(confirmText).toContain('工具节点需人工确认');
    expect(confirmText).not.toContain('AI_TOOL_CONFIRMATION_REQUIRED');

    expect(row(listRows, 1).text()).toContain('失败：AI_MODEL_CALL_FAILED');
    expect(row(listRows, 1).text()).toContain('320 毫秒');
    expect(row(listRows, 2).text()).toContain('运行中');
    // 缺耗时不编造 0：进度未知就显示占位符
    expect(row(listRows, 2).text()).toContain('—');
  });

  it('节点留痕：走独立接口并渲染，没有留痕时给受控结束文案', async () => {
    vi.mocked(getWorkflowRunPage).mockResolvedValue({
      list: [run(), run({ id: 302, status: 'SUCCEEDED' })],
      total: 2,
    });
    const wrapper = await openPanel(Runs, runPanelData());

    // 默认不展开留痕区
    expect(wrapper.text()).not.toContain('按执行顺序');

    await button(rowAt(wrapper, 0, 1, '运行行'), '节点留痕').trigger('click');
    await flushPromises();

    expect(getWorkflowRunNodeList).toHaveBeenCalledWith(302);
    expect(wrapper.text()).toContain(
      '该运行没有节点留痕（运行未开始执行即受控结束）',
    );

    vi.mocked(getWorkflowRunNodeList).mockResolvedValue([
      {
        durationMs: 12,
        errorCode: 'AI_NODE_RETRY_EXHAUSTED',
        nodeKey: 'retry',
        nodeType: 'MODEL',
        outputText: '模型重试耗尽',
        status: 'FAILED',
      },
      { nodeKey: 'a', nodeType: 'START', status: 'SUCCEEDED' },
    ]);
    await button(rowAt(wrapper, 0, 0, '运行行'), '节点留痕').trigger('click');
    await flushPromises();

    expect(getWorkflowRunNodeList).toHaveBeenLastCalledWith(301);
    const nodeRows = rowsOf(wrapper, 1);
    const nodeText = row(nodeRows, 0).text();
    expect(nodeText).toContain('retry');
    expect(nodeText).toContain('模型');
    expect(nodeText).toContain('失败');
    expect(nodeText).toContain('模型重试耗尽');
    expect(nodeText).toContain('AI_NODE_RETRY_EXHAUSTED');
    expect(nodeText).toContain('12 毫秒');
    // 白名单内的类型映射成中文名；输出与原因码缺值显示占位符
    expect(row(nodeRows, 1).text()).toContain('a开始成功');
    expect(row(nodeRows, 1).text()).toContain('—');
  });

  it('状态筛选重置到第一页，分页按钮按总数联动', async () => {
    vi.mocked(getWorkflowRunPage).mockResolvedValue({
      list: [run({ status: 'FAILED' })],
      total: 25,
    });
    const wrapper = await openPanel(Runs, runPanelData());

    expect(wrapper.text()).toContain('共 25 条');
    expect(button(wrapper, '上一页').attributes('disabled')).toBeDefined();
    expect(button(wrapper, '下一页').attributes('disabled')).toBeUndefined();

    await wrapper.find('select').setValue('FAILED');
    await flushPromises();
    expect(getWorkflowRunPage).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 20,
      status: 'FAILED',
      workflowId: 81,
    });

    await button(wrapper, '下一页').trigger('click');
    await flushPromises();
    expect(getWorkflowRunPage).toHaveBeenLastCalledWith({
      pageNo: 2,
      pageSize: 20,
      status: 'FAILED',
      workflowId: 81,
    });
    expect(wrapper.text()).toContain('第 2 页');
    expect(button(wrapper, '上一页').attributes('disabled')).toBeUndefined();
    // 2 * 20 >= 25：已经到底，最后一页的"下一页"必须禁用
    expect(button(wrapper, '下一页').attributes('disabled')).toBeDefined();
  });
});
