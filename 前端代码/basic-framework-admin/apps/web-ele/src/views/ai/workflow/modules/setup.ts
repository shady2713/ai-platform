import type { Mock } from 'vitest';

import type { DOMWrapper } from '@vue/test-utils';

import type {
  AiWorkflowApi,
  AiWorkflowRunApi,
  AiWorkflowVersionApi,
} from '#/api/ai/workflow';

import { flushPromises, mount } from '@vue/test-utils';

/**
 * 流程编排面板测试的共享助手与夹具（拆分自原 `modules.test.ts`）。
 *
 * <p>只放纯逻辑：这里不出现 `vi.mock`，也不持有 `vi.hoisted` 出来的 state。两者必须留在
 * 各自的测试文件里——`vi.mock` 只有写在被测 `.vue` 组件被 import 之前才生效，
 * 集中到这里会让模块提升顺序不可控；state 是那些 mock 的闭包，搬过来之后几个测试文件
 * 会共享同一套桩，用例之间互相污染。
 *
 * <p>因此依赖 state 的两个助手（`requireModalConfig` / `openPanel`）改由
 * {@link createHarness} 绑定：测试文件把自己那份 state 传进来，助手签名与拆分前一致，
 * 状态仍然一文件一份。
 *
 * <p>文件名刻意不带 `.test.`：vitest 只收集测试文件，所以这里是**装置**而不是会被执行的
 * 用例——拆分前后的用例总数因此保持不变。
 */

export interface ModalConfig {
  onConfirm?: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void> | void;
}

type Wrapper = ReturnType<typeof mount>;

/**
 * panel / 行两类宿主共有的最小能力：都能按选择器找子节点。
 *
 * <p>返回类型必须是 {@link DOMWrapper}，不能写成 `unknown[]`：整面板（VueWrapper）与
 * 某一行（DOMWrapper）的公共部分只有 `findAll`，但 `unknown[]` 会让 `button()` 的返回值
 * 退化成 `unknown`，`await button(row, '发布').trigger('click')` 这类调用随即变成类型错误。
 */
type Findable = { findAll: (selector: string) => DOMWrapper<Element>[] };

/**
 * 各测试文件 `vi.hoisted` 出来的装置结构。
 *
 * <p>在这里写死形状而不是让每个文件各自推断：字面量一旦与本类型不兼容
 * （例如某个 mock 换成有返回值的实现），会在类型检查阶段就暴露，
 * 而不是等到运行期某条断言拿到 `undefined`。
 */
export interface HarnessState {
  formApi: {
    getValues: Mock;
    resetForm: Mock;
    setValues: Mock;
    validate: Mock<() => Promise<{ valid: boolean }>>;
  };
  modalApi: { close: Mock; getData: Mock; setState: Mock };
  modalConfigs: ModalConfig[];
}

/** 测试助手：元素必须存在（找不到直接失败，避免非空断言）。 */
export function requireElement<T>(items: T[], index: number, what: string): T {
  const item = items[index];
  if (item === undefined) {
    throw new Error(`缺少${what}`);
  }
  return item;
}

/**
 * 测试助手：行数组的第 {@code index} 行，越界即失败。
 *
 * <p>存在的理由是把"下标可能越界"变成**可读失败**而不是 `undefined`：
 * 直接写 `rows[1]` 时，元素缺失会在几百行之后以一句
 * "Cannot read properties of undefined" 炸出来，看不出是哪个用例缺哪一行。
 * 严格模式（`vue-tsc`）会直接把它报成 TS2532，而 vitest 不会——
 * 所以这一层的价值是让"忘了加"在**类型检查**阶段就暴露。
 */
export function row<T>(items: T[], index: number): T {
  return requireElement(items, index, `第 ${index + 1} 行`);
}

/**
 * 测试助手：按可见文案找按钮。
 *
 * <p>入参放宽到 {@link Findable}：按钮既可能挂在整面板（VueWrapper）上，
 * 也可能挂在某一行（DOMWrapper）上，两者的公共部分只有 `findAll`。
 * 早先把签名写死成 VueWrapper，TypeScript 就会在"传行对象"的调用点上报错——
 * 那个报错是**签名错了**，不是用例错了。
 */
export function button(wrapper: Findable, text: string) {
  const found = wrapper
    .findAll('button')
    .find((candidate) => candidate.text() === text);
  if (!found) {
    throw new Error(`找不到按钮：${text}`);
  }
  return found;
}

/**
 * 测试助手：第 {@code which} 张表的第 {@code index} 行。
 *
 * <p>存在的理由是把"下标可能越界"变成**可读失败**而不是 `undefined`：
 * 直接写 `rows[1]` 时，元素缺失会在几百行之后以一句
 * "Cannot read properties of undefined" 炸出来，看不出是哪个用例缺哪一行。
 */
export function rowAt(
  wrapper: Findable,
  which: number,
  index: number,
  what: string,
) {
  return requireElement(
    rowsOf(wrapper, which),
    index,
    `${what}第 ${index + 1} 行`,
  );
}

/** 测试助手：首张表格的数据行（节点留痕是第二张表，不能混进来）。 */
export function rowsOf(wrapper: Findable, which = 0) {
  const table = requireElement(wrapper.findAll('table'), which, '表格');
  return table.findAll('tbody tr');
}

/** 测试助手：取弹窗标题。 */
export function modalTitle(wrapper: Wrapper): string {
  return requireElement(
    wrapper.findAll('[data-test="modal-title"]'),
    0,
    '弹窗标题',
  ).text();
}

/** 测试助手：版本面板的可写草稿图编辑区。 */
export function graphEditor(wrapper: Wrapper) {
  return requireElement(wrapper.findAll('textarea'), 0, '草稿图编辑区');
}

/** 把依赖装置的助手绑定到本测试文件自己的 state 上（state 由 vi.hoisted 产出）。 */
export function createHarness(state: HarnessState) {
  /** 测试助手：最近一次注册的弹窗配置必须存在。 */
  function requireModalConfig(): ModalConfig {
    const config = state.modalConfigs.at(-1);
    if (!config) {
      throw new Error('弹窗配置未注册');
    }
    return config;
  }

  /** 测试助手：挂载面板并按 setData 的意图打开，等价于列表页的 setData + open。 */
  async function openPanel(
    component: Parameters<typeof mount>[0],
    data: unknown,
    props: Record<string, unknown> = {},
  ): Promise<Wrapper> {
    state.modalConfigs.length = 0;
    state.modalApi.getData.mockReturnValue(data);
    const wrapper = mount(component, { props });
    await flushPromises();
    const modal = requireModalConfig();
    await modal.onOpenChange(true);
    await flushPromises();
    return wrapper;
  }

  return { openPanel, requireModalConfig };
}

export function workflow(
  overrides: Partial<AiWorkflowApi.Workflow> = {},
): AiWorkflowApi.Workflow {
  return {
    applicationId: 71,
    code: 'order-summary-flow',
    id: 81,
    name: '订单摘要生成流程',
    status: 'ENABLED',
    version: 3,
    ...overrides,
  };
}

export function version(
  overrides: Partial<AiWorkflowVersionApi.WorkflowVersion> = {},
): AiWorkflowVersionApi.WorkflowVersion {
  return {
    id: 91,
    status: 'DRAFT',
    version: 2,
    versionNo: 1,
    workflowId: 81,
    ...overrides,
  };
}

export function run(
  overrides: Partial<AiWorkflowRunApi.Run> = {},
): AiWorkflowRunApi.Run {
  return { id: 301, status: 'RUNNING', workflowId: 81, ...overrides };
}

/** 测试助手：运行面板的 setData 载荷（accept 区分"受理运行"与"只读查看"）。 */
export function runPanelData(accept = true) {
  return { accept, workflow: workflow() };
}

export const DRAFT_GRAPH = JSON.stringify({
  edges: [{ from: 'a', to: 'b' }],
  nodes: [
    { key: 'a', type: 'START' },
    { key: 'b', type: 'END' },
  ],
});

/** 形状预检会拒绝的图：解析失败 / 引用未声明节点 / 类型不在白名单 */
export const BAD_GRAPHS = [
  { graph: '{ 不是 json', reason: '流程图 JSON 无法解析，请检查引号与逗号' },
  {
    graph:
      '{"edges":[{"from":"a","to":"ghost"}],"nodes":[{"key":"a","type":"START"}]}',
    reason: '边 a → ghost 引用了未声明的节点',
  },
  {
    graph: '{"edges":[],"nodes":[{"key":"a","type":"SQL"}]}',
    reason: '节点 a 的类型不在冻结白名单内',
  },
];
