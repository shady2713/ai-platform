import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { callDebugEndpoint, issueDebugTicket } from '#/api/ai/open-platform';

import OpenPlatformIndex from './index.vue';

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
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
      state.formApi,
    ]),
  };
});

vi.mock('#/api/ai/open-platform', async () => {
  const actual = await vi.importActual<typeof import('#/api/ai/open-platform')>(
    '#/api/ai/open-platform',
  );
  return {
    ...actual,
    callDebugEndpoint: vi.fn(),
    issueDebugTicket: vi.fn(),
  };
});

/** O08 开放平台页面：目录只含已发布接口；调试不回显凭据；失败不伪造成功。 */
describe('ai open platform page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue({
      appCode: 'crm-portal',
      appSecret: 'aiapp_secret',
      endpointId: 'task-progress',
      externalUserId: 'u-1001',
      subjectType: 'USER',
    });
  });

  it('展示已发布接口目录与统计，未发布接口不出现', async () => {
    const wrapper = mount(OpenPlatformIndex);
    await flushPromises();

    const rows = wrapper.findAll('[data-testid="catalog-row"]');
    expect(rows.length).toBeGreaterThanOrEqual(15);
    expect(wrapper.text()).toContain('/app-api/ai/run/accept');
    expect(wrapper.text()).toContain('SSE 事件流');
    expect(wrapper.text()).toContain('异步受理');
    expect(wrapper.text()).not.toContain('/admin-api');
    expect(wrapper.text()).not.toContain('not-published');
  });

  it('详情展示鉴权、限额与示例，并提示 SSE 与异步语义', async () => {
    const wrapper = mount(OpenPlatformIndex);
    await flushPromises();

    const detailButton = wrapper
      .findAll('button')
      .find((item) => item.text() === '查看详情');
    await detailButton?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('鉴权');
    expect(wrapper.text()).toContain('token 只在本次响应返回一次');
  });

  it('调试用短期票据调用开放接口且不回显凭据或令牌', async () => {
    vi.mocked(issueDebugTicket).mockResolvedValue({
      token: 'aitkt_once',
      subjectType: 'USER',
    });
    vi.mocked(callDebugEndpoint).mockResolvedValue({
      body: '{"code":0,"data":{"runId":41,"status":"FAILED"},"msg":""}',
      status: 200,
    });
    const wrapper = mount(OpenPlatformIndex);
    await flushPromises();

    const run = wrapper
      .findAll('button')
      .find((item) => item.text() === '执行调试');
    await run?.trigger('click');
    await flushPromises();

    expect(issueDebugTicket).toHaveBeenCalledWith(
      'crm-portal',
      'aiapp_secret',
      'USER',
      'u-1001',
    );
    expect(callDebugEndpoint).toHaveBeenCalledWith(
      'aitkt_once',
      'GET',
      '/app-api/ai/task/progress',
    );
    const result = wrapper.find('[data-testid="debug-result"]').text();
    expect(result).toContain('"runId":41');
    // 响应区域不回显应用秘密与令牌
    expect(wrapper.text()).not.toContain('aiapp_secret');
    expect(wrapper.text()).not.toContain('aitkt_once');
    expect(wrapper.text()).toContain('票据只驻留内存');
  });

  it('调试失败只提示失败，不渲染结果也不回显凭据', async () => {
    vi.mocked(issueDebugTicket).mockRejectedValue(new Error('401'));
    const wrapper = mount(OpenPlatformIndex);
    await flushPromises();

    const run = wrapper
      .findAll('button')
      .find((item) => item.text() === '执行调试');
    await run?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('调试失败');
    expect(wrapper.find('[data-testid="debug-result"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('aiapp_secret');
  });

  it('校验不通过时不发起调试', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    const wrapper = mount(OpenPlatformIndex);
    await flushPromises();

    const run = wrapper
      .findAll('button')
      .find((item) => item.text() === '执行调试');
    await run?.trigger('click');
    await flushPromises();

    expect(issueDebugTicket).not.toHaveBeenCalled();
    expect(callDebugEndpoint).not.toHaveBeenCalled();
  });
});
