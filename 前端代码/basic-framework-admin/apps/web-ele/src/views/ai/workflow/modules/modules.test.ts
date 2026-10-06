import type { ModalConfig } from './setup';

import { flushPromises } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createWorkflowDraft,
  discardWorkflowDraft,
  getOpenWorkflowDraft,
  getWorkflowVersion,
  getWorkflowVersionPage,
  publishWorkflowVersion,
  updateWorkflowDraft,
} from '#/api/ai/workflow';
import { showSuccessMessage } from '#/utils/feedback';

import { WORKFLOW_GRAPH_EXAMPLE } from '../data';
import {
  BAD_GRAPHS,
  button,
  createHarness,
  DRAFT_GRAPH,
  graphEditor,
  modalTitle,
  requireElement,
  row,
  rowAt,
  rowsOf,
  version,
  workflow,
} from './setup';
import Versions from './versions.vue';

/**
 * 版本管理面板（`versions.vue`）的用例。
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

describe('ai workflow modules · 版本管理面板', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfigs.length = 0;
    state.modalApi.getData.mockReturnValue(workflow());
    vi.mocked(getOpenWorkflowDraft).mockResolvedValue(null);
    vi.mocked(getWorkflowVersionPage).mockResolvedValue({ list: [], total: 0 });
  });

  it('打开面板：按传入的流程拉版本页与打开中的草稿', async () => {
    const wrapper = await openPanel(Versions, workflow());

    expect(getWorkflowVersionPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      workflowId: 81,
    });
    expect(getOpenWorkflowDraft).toHaveBeenCalledWith(81);
    expect(modalTitle(wrapper)).toBe('版本管理 · 订单摘要生成流程');
    // 没有打开的草稿时编辑区回落到示例图，且预检结论是"通过"
    expect(graphEditor(wrapper).element.value).toBe(WORKFLOW_GRAPH_EXAMPLE);
    expect(wrapper.text()).toContain(
      '形状与规模校验通过（环/无出口/类型由发布期服务端校验）',
    );
    expect(wrapper.text()).toContain(
      '还没有版本；先创建草稿并发布后才能被受理运行。',
    );
  });

  it('没有流程上下文时不发任何请求', async () => {
    const wrapper = await openPanel(Versions, undefined);
    expect(getWorkflowVersionPage).not.toHaveBeenCalled();
    expect(getOpenWorkflowDraft).not.toHaveBeenCalled();
    expect(modalTitle(wrapper)).toBe('版本管理');
  });

  it('创建草稿：把编辑区图 JSON 原样提交', async () => {
    vi.mocked(createWorkflowDraft).mockResolvedValue(91);
    const wrapper = await openPanel(Versions, workflow());
    await button(wrapper, '创建草稿').trigger('click');
    await flushPromises();
    expect(createWorkflowDraft).toHaveBeenCalledWith({
      graphJson: WORKFLOW_GRAPH_EXAMPLE,
      workflowId: 81,
    });
    expect(showSuccessMessage).toHaveBeenCalledWith('已创建草稿');
    // 保存后要重新对齐"单开草稿"约束与列表
    expect(getOpenWorkflowDraft).toHaveBeenCalledTimes(2);
    expect(getWorkflowVersionPage).toHaveBeenCalledTimes(2);
  });

  it('形状预检不过时拒绝提交，并原样显示原因', async () => {
    // 复用同一个挂载：graphCheck 是 computed，改编辑区就该立刻重算，
    // 不需要每换一个用例就重新走一遍 onOpenChange
    const wrapper = await openPanel(Versions, workflow());
    for (const { graph, reason } of BAD_GRAPHS) {
      await graphEditor(wrapper).setValue(graph);
      await button(wrapper, '创建草稿').trigger('click');
      await flushPromises();

      expect(createWorkflowDraft).not.toHaveBeenCalled();
      expect(wrapper.text()).toContain(reason);
    }
  });

  it('已有打开的草稿：按钮变成保存草稿并走 update-draft', async () => {
    vi.mocked(getOpenWorkflowDraft).mockResolvedValue(
      version({ graphJson: DRAFT_GRAPH }),
    );
    vi.mocked(updateWorkflowDraft).mockResolvedValue(true);
    const wrapper = await openPanel(Versions, workflow());
    // 编辑区被草稿图覆盖，避免用户误把示例图保存进去
    expect(graphEditor(wrapper).element.value).toBe(DRAFT_GRAPH);
    expect(wrapper.text()).toContain('草稿图（编辑打开中的草稿）');
    await button(wrapper, '保存草稿图').trigger('click');
    await flushPromises();
    expect(updateWorkflowDraft).toHaveBeenCalledWith({
      graphJson: DRAFT_GRAPH,
      version: 2,
      versionId: 91,
      workflowId: 81,
    });
    expect(createWorkflowDraft).not.toHaveBeenCalled();
    expect(showSuccessMessage).toHaveBeenCalledWith('已保存草稿图');
  });

  it('发布失败原样回显稳定原因码，成功时回显版本号', async () => {
    vi.mocked(getWorkflowVersionPage).mockResolvedValue({
      list: [
        version({ edgeCount: 1, graphHash: 'abcdef0123456789', nodeCount: 2 }),
      ],
      total: 1,
    });
    vi.mocked(publishWorkflowVersion).mockRejectedValue({
      response: { data: { msg: 'AI_WORKFLOW_GRAPH_CYCLE' } },
    });
    const wrapper = await openPanel(Versions, workflow());

    const row = requireElement(rowsOf(wrapper), 0, '版本行');
    // 图摘要只展示前 12 位，状态与节点/边计数来自真实后端字段
    const rowText = row.text();
    expect(rowText).toContain('v1');
    expect(rowText).toContain('草稿');
    expect(rowText).toContain('2/1');
    expect(rowText).toContain('abcdef012345');
    expect(rowText).toContain('—');
    await button(row, '发布').trigger('click');
    await flushPromises();
    expect(publishWorkflowVersion).toHaveBeenCalledWith({ id: 91, version: 2 });
    expect(wrapper.text()).toContain('AI_WORKFLOW_GRAPH_CYCLE');
    expect(wrapper.text()).not.toContain('已发布版本');
    // 失败后仍要刷新列表：发布结果可能已经改变版本状态
    expect(getWorkflowVersionPage).toHaveBeenCalledTimes(2);
    vi.mocked(publishWorkflowVersion).mockResolvedValue(1);
    await button(rowAt(wrapper, 0, 0, '版本行'), '发布').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('已发布版本 v1');
    expect(wrapper.text()).not.toContain('AI_WORKFLOW_GRAPH_CYCLE');
  });

  it('废弃草稿带乐观锁版本，失败时回显原因', async () => {
    vi.mocked(getWorkflowVersionPage).mockResolvedValue({
      list: [version()],
      total: 1,
    });
    vi.mocked(discardWorkflowDraft).mockRejectedValue({
      response: { data: { msg: 'AI_WORKFLOW_VERSION_NOT_DRAFT' } },
    });
    const wrapper = await openPanel(Versions, workflow());

    await button(rowAt(wrapper, 0, 0, '版本行'), '废弃').trigger('click');
    await flushPromises();

    expect(discardWorkflowDraft).toHaveBeenCalledWith({ id: 91, version: 2 });
    expect(wrapper.text()).toContain('AI_WORKFLOW_VERSION_NOT_DRAFT');
    expect(showSuccessMessage).not.toHaveBeenCalled();
  });

  it('只读查看已发布版本；草稿的编辑载入编辑区而不是只读区', async () => {
    const PUBLISHED_GRAPH = '{"nodes":[{"key":"p","type":"START"}]}';
    vi.mocked(getWorkflowVersionPage).mockResolvedValue({
      list: [
        version({ graphJson: DRAFT_GRAPH }),
        version({
          graphHash: 'fedcba9876543210',
          graphJson: PUBLISHED_GRAPH,
          id: 92,
          publishedAt: '2026-09-01 10:00:00',
          status: 'PUBLISHED',
          version: 1,
          versionNo: 2,
        }),
      ],
      total: 2,
    });
    const wrapper = await openPanel(Versions, workflow());

    const allRows = rowsOf(wrapper);
    // 已发布版本只有"查看"，没有编辑/发布/废弃：版本是不可变快照
    expect(
      row(allRows, 1)
        .findAll('button')
        .map((b) => b.text()),
    ).toStrictEqual(['查看']);
    expect(row(allRows, 1).text()).toContain('已发布');
    expect(row(allRows, 1).text()).toContain('2026-09-01 10:00:00');

    // 只读区的标题用的是**详情响应**里的状态，不是列表行上的那个
    vi.mocked(getWorkflowVersion).mockResolvedValue(
      version({
        graphJson: PUBLISHED_GRAPH,
        status: 'PUBLISHED',
        versionNo: 2,
      }),
    );
    await button(row(allRows, 1), '查看').trigger('click');
    await flushPromises();

    expect(getWorkflowVersion).toHaveBeenCalledWith(92);
    expect(wrapper.text()).toContain('只读查看：v2（已发布）');
    expect(wrapper.findAll('textarea')).toHaveLength(2);
    expect(row(wrapper.findAll('textarea'), 1).element.value).toBe(
      PUBLISHED_GRAPH,
    );

    vi.mocked(getWorkflowVersion).mockResolvedValue(
      version({ graphJson: DRAFT_GRAPH }),
    );
    await button(rowAt(wrapper, 0, 0, '版本行'), '编辑').trigger('click');
    await flushPromises();

    expect(getWorkflowVersion).toHaveBeenLastCalledWith(91);
    // 草稿图回到可写编辑区，同时清掉只读查看区，避免两处图同时占屏
    expect(graphEditor(wrapper).element.value).toBe(DRAFT_GRAPH);
    expect(wrapper.findAll('textarea')).toHaveLength(1);
    expect(wrapper.text()).not.toContain('只读查看');
  });
});
