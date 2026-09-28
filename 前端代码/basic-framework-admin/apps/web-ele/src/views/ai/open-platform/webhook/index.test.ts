import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  createTarget: vi.fn(),
  deleteTarget: vi.fn(),
  getDeliveryAttempts: vi.fn(),
  getDeliveryPage: vi.fn(),
  getTargetPage: vi.fn(),
  redeliverDelivery: vi.fn(),
  rotateTargetSecret: vi.fn(),
  updateTargetStatus: vi.fn(),
}));

const access = vi.hoisted(() => ({
  hasAccessByCodes: vi.fn<(codes: string[]) => boolean>(),
}));

const applicationApi = vi.hoisted(() => ({ getApplicationPage: vi.fn() }));

// 本文件挂载完整控制台（表单/表格组件栈较重）：默认 5s 超时在全量套件并行运行时会被撑爆，
// 这里显式放宽到 20s；断言内容与行为不变（不是用超时掩盖失败）。
vi.setConfig({ testTimeout: 20_000 });

vi.mock('./api', () => api);

vi.mock('#/api/ai/application', () => applicationApi);

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: access.hasAccessByCodes }),
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', { 'data-testid': 'page' }, slots.default?.());
      },
    }),
  };
});

const { default: WebhookConsole } = await import('./index.vue');

function target(overrides: Record<string, unknown> = {}) {
  return {
    applicationId: 7,
    code: 'erp-callback',
    id: 91,
    maxAttempts: 3,
    name: 'ERP 回调',
    secretConfigured: true,
    secretRevision: 2,
    status: 'ENABLED',
    targetUrl: 'https://erp.example.com/hook',
    eventTypes: ['RUN.SUCCEEDED', 'RUN.FAILED'],
    version: 3,
    ...overrides,
  };
}

function delivery(overrides: Record<string, unknown> = {}) {
  return {
    applicationId: 7,
    attemptCount: 2,
    deliveryNo: 'whd_aabbccddeeff00112233445566778899',
    eventType: 'RUN.SUCCEEDED',
    failureCode: '1_003_011_006',
    id: 55,
    lastErrorCode: 'http-server-error',
    maxAttempts: 2,
    payloadDigest: 'a'.repeat(64),
    resourceKey: 'run_abc',
    resourceType: 'RUN',
    status: 'FAILED',
    targetId: 91,
    ...overrides,
  };
}

async function render() {
  const wrapper = mount(WebhookConsole);
  await flushPromises();
  return wrapper;
}

describe('webhook 投递控制台（X10）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    access.hasAccessByCodes.mockReturnValue(true);
    applicationApi.getApplicationPage.mockResolvedValue({
      list: [{ appCode: 'crm-portal', id: 7, name: 'CRM 门户' }],
      total: 1,
    });
    api.getTargetPage.mockResolvedValue({ list: [target()], total: 1 });
    api.getDeliveryPage.mockResolvedValue({ list: [delivery()], total: 1 });
    api.getDeliveryAttempts.mockResolvedValue([
      { attemptNo: 1, outcome: 'RETRYABLE', httpStatus: 500, durationMs: 12 },
      { attemptNo: 2, outcome: 'RETRYABLE', httpStatus: 500, durationMs: 15 },
    ]);
    api.updateTargetStatus.mockResolvedValue(true);
    api.redeliverDelivery.mockResolvedValue(true);
    api.rotateTargetSecret.mockResolvedValue(true);
    api.createTarget.mockResolvedValue(91);
  });

  it('展示目标与投递事实，死信与稳定原因码可见', async () => {
    const wrapper = await render();
    const text = wrapper.text();

    expect(text).toContain('erp-callback');
    expect(text).toContain('https://erp.example.com/hook');
    expect(text).toContain('运行成功');
    expect(text).toContain('死信 1');
    expect(text).toContain('接收端错误（5xx）');
    expect(text).toContain('重试预算已耗尽（死信）');
    // 正文只以摘要形式出现，完整摘要与投递正文都不渲染
    expect(text).toContain('aaaaaaaaaaaa…');
    expect(text).not.toContain('a'.repeat(64));
    expect(text).not.toContain('schemaVersion');
  });

  it('停用目标把版本一起提交，并提示停发语义', async () => {
    const wrapper = await render();

    await wrapper.get('[data-testid="ai-webhook-toggle-91"]').trigger('click');
    await flushPromises();

    expect(api.updateTargetStatus).toHaveBeenCalledWith({
      enabled: false,
      id: 91,
      version: 3,
    });
    expect(wrapper.get('[data-testid="ai-webhook-notice"]').text()).toContain(
      '不再产生投递',
    );
    expect(api.getTargetPage).toHaveBeenCalledTimes(2);
  });

  it('人工重投只在死信上出现，并带上投递编号', async () => {
    const wrapper = await render();

    await wrapper
      .get('[data-testid="ai-webhook-redeliver-55"]')
      .trigger('click');
    await flushPromises();

    expect(api.redeliverDelivery).toHaveBeenCalledWith(55);
    expect(wrapper.get('[data-testid="ai-webhook-notice"]').text()).toContain(
      '重置尝试预算',
    );
  });

  it('没有重投权限时不出现人工重投按钮', async () => {
    access.hasAccessByCodes.mockImplementation(
      (codes: string[]) => !codes.includes('ai:webhook:redeliver'),
    );
    const wrapper = await render();

    expect(
      wrapper.find('[data-testid="ai-webhook-redeliver-55"]').exists(),
    ).toBe(false);
  });

  it('投递详情展示每一次尝试的结论与耗时', async () => {
    const wrapper = await render();

    const detailButtons = wrapper.findAll('button');
    const detail = detailButtons.find((button) => button.text() === '详情');
    expect(detail).toBeDefined();
    await detail?.trigger('click');
    await flushPromises();

    expect(api.getDeliveryAttempts).toHaveBeenCalledWith(55);
    expect(wrapper.get('[data-testid="ai-webhook-attempts"]').text()).toContain(
      '可重试',
    );
  });

  it('登记目标做本地形状校验并把归一化入参交给服务端', async () => {
    const wrapper = await render();

    await wrapper.get('[data-testid="ai-webhook-create"]').trigger('click');
    await wrapper.get('[data-testid="ai-webhook-form-code"]').setValue('srv');
    await wrapper
      .get('[data-testid="ai-webhook-form-url"]')
      .setValue('https://user:pass@erp.example.com/hook');
    await wrapper
      .get('[data-testid="ai-webhook-form-secret"]')
      .setValue('s3cret-signing-key-0123456789');
    await flushPromises();

    const submit = wrapper
      .findAll('button')
      .find((button) => button.text() === '提交');
    await submit?.trigger('click');
    await flushPromises();

    // 带 URL 凭据信息的地址在本地就被拦下，不发请求
    expect(api.createTarget).not.toHaveBeenCalled();
  });

  it('读取失败时给出稳定提示而不是伪造空数据', async () => {
    api.getTargetPage.mockRejectedValue(new Error('forbidden'));
    const wrapper = await render();

    expect(wrapper.get('[data-testid="ai-webhook-error"]').text()).toContain(
      'ai:webhook:query',
    );
  });
});
