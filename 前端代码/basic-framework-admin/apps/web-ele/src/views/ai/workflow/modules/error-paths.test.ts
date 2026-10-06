import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import Runs from './runs.vue';
import Versions from './versions.vue';

/**
 * 面板错误路径：失败必须给出用户可见的消息。
 *
 * <p>与 `modules.test.ts` 分文件而不是塞进去，是因为那一份已经 799 行（上限 800），
 * 而"失败时是否静默"是一个独立关注点，单独成文件更好定位。
 *
 * <p>为什么必须有这层用例：此前 `handleEdit` / `handleView` / `handleNodes` 直接
 * `await` 端点、没有 `catch`。一次失败请求会变成**没有用户可见消息的未捕获拒绝**，
 * 而同一面板的 `handleSaveDraft` / `handlePublish` / `handleDiscard` / `handleAccept`
 * 都用 `extractErrorMessage` 如实显示稳定原因码——同一份代码里两种失败口径说不通。
 * `handleNodes` 更糟：失败后上一个运行的留痕会留在页面上冒充本次的。
 */

interface ModalConfig {
  onOpenChange: (isOpen: boolean) => Promise<void> | void;
}

type Wrapper = ReturnType<typeof mount>;

const api = vi.hoisted(() => ({
  getWorkflowRunNodeList: vi.fn(),
  getWorkflowRunPage: vi.fn(),
  getWorkflowVersion: vi.fn(),
  getWorkflowVersionPage: vi.fn(),
  getOpenWorkflowDraft: vi.fn(),
}));

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
  const actual =
    await vi.importActual<typeof import('@vben/common-ui')>('@vben/common-ui');
  const { defineComponent, h } = await import('vue');
  const ModalStub = defineComponent({
    name: 'ModalStub',
    setup:
      (_props, { slots }) =>
      () =>
        h('section', { 'data-test': 'modal' }, slots.default?.()),
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

vi.mock('#/api/ai/workflow', () => api);

// extractErrorMessage 必须保持真实实现：面板"失败永远显示稳定原因码"这条契约
// 就落在它身上，换成桩函数等于把要测的行为换成了自己写的假行为。
vi.mock('#/utils/feedback', async () => {
  const actual =
    await vi.importActual<typeof import('#/utils/feedback')>(
      '#/utils/feedback',
    );
  return { ...actual, showSuccessMessage: vi.fn() };
});
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('@vben/utils', () => ({ cloneDeep: (value: unknown) => value }));

const WORKFLOW = {
  code: 'order-summary',
  id: 81,
  name: '订单摘要生成流程',
};

/**
 * 后端稳定错误码的**真实**形态。
 *
 * <p>必须用 `{ response: { data: { msg } } }` 而不是 `new Error()` + 自定义字段：
 * `extractErrorMessage` 是沿 `response.data.msg → data.msg → message` 这条链取值的，
 * 换成别的形状就等于在测一个不存在的行为。
 */
function serviceError(msg: string) {
  return { response: { data: { msg } } };
}

async function openPanel(
  component: Parameters<typeof mount>[0],
  data: unknown,
): Promise<Wrapper> {
  state.modalConfigs.length = 0;
  state.modalApi.getData.mockReturnValue(data);
  const wrapper = mount(component);
  await flushPromises();
  const config = state.modalConfigs.at(-1);
  if (!config) {
    throw new Error('弹窗配置未注册');
  }
  await config.onOpenChange(true);
  await flushPromises();
  return wrapper;
}

function button(wrapper: Wrapper, text: string) {
  const found = wrapper.findAll('button').find((b) => b.text() === text);
  if (!found) {
    throw new Error(`未找到按钮「${text}」`);
  }
  return found;
}

describe('流程编排面板 · 错误路径', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getWorkflowVersionPage.mockResolvedValue({ list: [], total: 0 });
    api.getOpenWorkflowDraft.mockResolvedValue(undefined);
    api.getWorkflowRunPage.mockResolvedValue({ list: [], total: 0 });
  });

  it('查看版本失败时显示原因码，而不是静默无反应', async () => {
    api.getWorkflowVersionPage.mockResolvedValue({
      list: [{ id: 92, status: 'PUBLISHED', versionNo: 2 }],
      total: 1,
    });
    api.getWorkflowVersion.mockRejectedValue(
      serviceError('AI_WORKFLOW_VERSION_NOT_FOUND'),
    );
    const wrapper = await openPanel(Versions, WORKFLOW);

    await button(wrapper, '查看').trigger('click');
    await flushPromises();

    // 断言**稳定原因码**而不是 fallback 文案：错误带码时 extractErrorMessage 返回码，
    // fallback 只在没有码时兜底——断言错的那一个就测不到任何东西。
    expect(wrapper.text()).toContain('AI_WORKFLOW_VERSION_NOT_FOUND');
    // 不能把 undefined 渲染到界面上
    expect(wrapper.text()).not.toContain('undefined');
  });

  it('载入草稿失败时显示原因码', async () => {
    api.getWorkflowVersionPage.mockResolvedValue({
      list: [{ id: 91, status: 'DRAFT', versionNo: 1 }],
      total: 1,
    });
    api.getWorkflowVersion.mockRejectedValue(
      serviceError('AI_WORKFLOW_VERSION_NOT_DRAFT'),
    );
    const wrapper = await openPanel(Versions, WORKFLOW);

    await button(wrapper, '编辑').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('AI_WORKFLOW_VERSION_NOT_DRAFT');
  });

  it('读取节点留痕失败时显示原因码，且不留下上一个运行的留痕', async () => {
    api.getWorkflowRunPage.mockResolvedValue({
      list: [{ id: 55, status: 'SUCCEEDED' }],
      total: 1,
    });
    api.getWorkflowRunNodeList.mockRejectedValue(
      serviceError('AI_WORKFLOW_RUN_NOT_FOUND'),
    );
    const wrapper = await openPanel(Runs, {
      accept: false,
      workflow: WORKFLOW,
    });

    await button(wrapper, '节点留痕').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('AI_WORKFLOW_RUN_NOT_FOUND');
    // 失败后清空：否则上一次点开的留痕会留在页面上冒充本次的
    expect(wrapper.text()).not.toContain('START');
  });
});
