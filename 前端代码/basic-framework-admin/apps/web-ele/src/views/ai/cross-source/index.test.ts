import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

// 只替换 HTTP 客户端，不替换 `#/api/ai/cross-source`：
// 这样页面走的是真实 API 层，"executionKey 两处都带"那条 URL 契约才测得到。
const request = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('#/api/request', () => ({ requestClient: { get: request.get } }));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', slots.default?.());
      },
    }),
  };
});

const { default: CrossSourceIndex } = await import('./index.vue');

const EXECUTION_KEY = 'xs-merge-aaa';

/** 完整放行的结果：数字齐全，用来证明"不渲染数字"的断言不是空断言 */
const completeResult = {
  complete: true,
  consistencyAsOf: '2026-09-30T10:00:00',
  crossSource: true,
  currency: 'CNY',
  executionKey: EXECUTION_KEY,
  integrity: { state: 'COMPLETE' as const },
  maxSkewMillis: 45_000,
  metricCode: 'gross_revenue',
  sourceCount: 2,
  sources: [
    { amount: 4567.89, role: 'CRM' },
    { amount: 6543.21, role: 'ERP' },
  ],
  totalAmount: 11_111.1,
};

/**
 * 契约被违反的响应：口径是 WITHHELD，却混回了合计、来源数与分来源明细。
 * 页面必须不渲染其中任何一个数字——fail-closed 不该依赖"服务端永远不发"。
 */
const leakyWithheldResult = {
  ...completeResult,
  integrity: {
    reason: '部分来源不在你的授权范围内，该结果未出具。',
    state: 'WITHHELD' as const,
  },
};

/** 已发出的请求（[url, config]），用于断言 URL 与查询串 */
function calls(): Array<[string, Record<string, unknown>]> {
  return request.get.mock.calls as Array<[string, Record<string, unknown>]>;
}

/**
 * 赋值到真正承载 v-model 的 `<input>` 上。
 * ElInput 声明了 `inheritAttrs: false` 并把非 class/style 的 attrs 透传到内层
 * `<input>`；ElInputNumber 没有声明，attrs 落在自己的根 div 上。
 * 两种形态都得能取值，所以这里统一向下找到 `<input>` 再赋值。
 */
async function setInput(
  wrapper: ReturnType<typeof mount>,
  testid: string,
  value: string,
): Promise<void> {
  const node = wrapper.find(`[data-testid="${testid}"]`);
  const input = node.element.tagName === 'INPUT' ? node : node.find('input');
  await input.setValue(value);
}

/** ElSelect 根节点是 div：用组件事件驱动 v-model，而不是 setValue */
function setSelect(
  wrapper: ReturnType<typeof mount>,
  testid: string,
  value: unknown,
): void {
  const select = wrapper.findComponent(
    `[data-testid="${testid}"]`,
  ) as unknown as {
    vm: { $emit: (event: string, payload: unknown) => void };
  };
  select.vm.$emit('update:modelValue', value);
}

async function fillQuery(
  wrapper: ReturnType<typeof mount>,
  options: { callerRoles?: string[] } = {},
): Promise<void> {
  await setInput(wrapper, 'ai-cross-source-execution-key', EXECUTION_KEY);
  await setInput(wrapper, 'ai-cross-source-application-id', '42');
  await setInput(wrapper, 'ai-cross-source-external-user-id', 'user-1');
  setSelect(
    wrapper,
    'ai-cross-source-caller-roles',
    options.callerRoles ?? ['ANALYST'],
  );
  await flushPromises();
}

async function probe(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.find('[data-testid="ai-cross-source-probe"]').trigger('click');
  await flushPromises();
}

async function fetchResult(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.find('[data-testid="ai-cross-source-fetch"]').trigger('click');
  await flushPromises();
}

async function mountPage() {
  const wrapper = mount(CrossSourceIndex);
  await flushPromises();
  return wrapper;
}

describe('跨源合并结果页（V07）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    request.get.mockResolvedValue({ state: 'COMPLETE' });
  });

  it('「先查口径」打的是 /integrity，且 executionKey 在路径与查询串两处都带', async () => {
    const wrapper = await mountPage();
    await fillQuery(wrapper);
    await probe(wrapper);

    expect(calls()).toHaveLength(1);
    const [url, config] = calls()[0] ?? [];
    // 路径变量
    expect(url).toBe(`/ai/cross-source/results/${EXECUTION_KEY}/integrity`);
    // @RequestParam：后端签名要求它在查询串里也在，漏了就是 400
    const params = config?.params as Record<string, unknown>;
    expect(params?.executionKey).toBe(EXECUTION_KEY);
    expect(params?.applicationId).toBe(42);
    expect(params?.subjectType).toBe('USER');
    expect(params?.externalUserId).toBe('user-1');
    // 角色是可重复参数，保持数组交给 repeat 序列化器，不做逗号拼接
    expect(params?.callerRoles).toEqual(['ANALYST']);
    // 没填的可选参数不写进查询串
    expect(params?.previouslySeenRoles).toBeUndefined();
    expect(
      wrapper.get('[data-testid="ai-cross-source-integrity-state"]').text(),
    ).toContain('COMPLETE');
  });

  it('此前已见角色按可重复参数带上（合计覆盖口径，差额可解的输入）', async () => {
    const wrapper = await mountPage();
    await fillQuery(wrapper);
    setSelect(wrapper, 'ai-cross-source-previously-seen-roles', [
      'AGGREGATE_READER',
      'ANALYST',
    ]);
    await flushPromises();
    await probe(wrapper);

    const params = calls()[0]?.[1]?.params as Record<string, unknown>;
    expect(params?.previouslySeenRoles).toEqual([
      'AGGREGATE_READER',
      'ANALYST',
    ]);
  });

  it('口径未放行时不发结果请求，取数按钮保持禁用', async () => {
    request.get.mockResolvedValue({
      reason: '部分来源不在你的授权范围内，该结果未出具。',
      state: 'WITHHELD',
    });
    const wrapper = await mountPage();
    await fillQuery(wrapper);
    await probe(wrapper);

    // 只发了口径那一个请求
    expect(calls()).toHaveLength(1);
    expect(calls()[0]?.[0]).toContain('/integrity');
    expect(
      wrapper
        .find('[data-testid="ai-cross-source-fetch"]')
        .attributes('disabled'),
    ).toBeDefined();
    expect(
      wrapper.find('[data-testid="ai-cross-source-amounts"]').exists(),
    ).toBe(false);
    expect(
      wrapper.get('[data-testid="ai-cross-source-integrity-reason"]').text(),
    ).toContain('不在你的授权范围内');
  });

  it('结果口径为 WITHHELD 时一个数字都不渲染（fail-closed 承重用例）', async () => {
    const wrapper = await mountPage();
    await fillQuery(wrapper);
    await probe(wrapper);
    // 口径放行后取数，但服务端回的是未出具且混回了数字的响应
    request.get.mockResolvedValue(leakyWithheldResult);
    await fetchResult(wrapper);

    // 数字区整块不存在（不是被 CSS 藏起来）
    expect(
      wrapper.find('[data-testid="ai-cross-source-amounts"]').exists(),
    ).toBe(false);
    // 逐个把响应里的数字挡在页面之外
    const text = wrapper.text();
    expect(text).not.toContain('11111.1');
    expect(text).not.toContain('4567.89');
    expect(text).not.toContain('6543.21');
    expect(text).not.toContain('45000');
    expect(text).not.toContain('2026-09-30');
    // 来源计数也是一条信道：连"来源数"这个标签都不出现
    expect(text).not.toContain('来源数');
    expect(text).not.toContain('合计金额');
    // 说明区本身也不得出现任何数字
    const notice = wrapper
      .get('[data-testid="ai-cross-source-withheld"]')
      .text();
    expect(notice).not.toMatch(/\d/);
    expect(notice).toContain('未出具');
  });

  it('口径 COMPLETE 时如实渲染合计、来源数与一致性字段（证明上一条断言不空）', async () => {
    const wrapper = await mountPage();
    await fillQuery(wrapper);
    await probe(wrapper);
    request.get.mockResolvedValue(completeResult);
    await fetchResult(wrapper);

    expect(calls()[1]?.[0]).toBe(`/ai/cross-source/results/${EXECUTION_KEY}`);
    const text = wrapper.get('[data-testid="ai-cross-source-amounts"]').text();
    expect(text).toContain('合计金额：11111.1');
    expect(text).toContain('来源数：2');
    expect(text).toContain('gross_revenue');
    expect(text).toContain('CNY');
    // 跨源一致性字段如实展示，让用户自己判断数据新鲜度
    expect(text).toContain('口径时间点：2026-09-30 10:00:00');
    expect(text).toContain('最大时间偏移：45000 毫秒');
    expect(
      wrapper.find('[data-testid="ai-cross-source-sources"]').exists(),
    ).toBe(true);
    expect(
      wrapper.get('[data-testid="ai-cross-source-sources"]').text(),
    ).toContain('4567.89');
  });

  it('后端漏发口径时按未出具渲染（不按 COMPLETE 放行）', async () => {
    const wrapper = await mountPage();
    await fillQuery(wrapper);
    // 跨源标记在、口径不在：与单系统响应无法区分时必须 fail-closed
    request.get.mockResolvedValue({
      crossSource: true,
      executionKey: EXECUTION_KEY,
    });
    await probe(wrapper);

    expect(
      wrapper.get('[data-testid="ai-cross-source-integrity-state"]').text(),
    ).toContain('MISSING');
    expect(
      wrapper
        .find('[data-testid="ai-cross-source-fetch"]')
        .attributes('disabled'),
    ).toBeDefined();
  });

  it('角色为空时在页面侧就拦下，不把注定被拒的请求发出去', async () => {
    const wrapper = await mountPage();
    await setInput(wrapper, 'ai-cross-source-execution-key', EXECUTION_KEY);
    await setInput(wrapper, 'ai-cross-source-application-id', '42');
    await setInput(wrapper, 'ai-cross-source-external-user-id', 'user-1');
    await probe(wrapper);

    expect(request.get).not.toHaveBeenCalled();
    expect(
      wrapper.get('[data-testid="ai-cross-source-form-error"]').text(),
    ).toContain('至少选择一个调用方角色');
  });

  it('改了查询条件就作废上一次口径（旧口径不得放行新参数）', async () => {
    const wrapper = await mountPage();
    await fillQuery(wrapper);
    await probe(wrapper);
    expect(
      wrapper
        .find('[data-testid="ai-cross-source-fetch"]')
        .attributes('disabled'),
    ).toBeUndefined();

    // 换执行键：上一组参数的口径不再作数
    await setInput(wrapper, 'ai-cross-source-execution-key', 'xs-merge-bbb');
    await flushPromises();

    expect(
      wrapper.find('[data-testid="ai-cross-source-integrity-state"]').exists(),
    ).toBe(false);
    expect(
      wrapper
        .find('[data-testid="ai-cross-source-fetch"]')
        .attributes('disabled'),
    ).toBeDefined();
  });

  it('读取口径失败时说明权限要求，且不留下一个可取数的假口径', async () => {
    request.get.mockRejectedValue(new Error('forbidden'));
    const wrapper = await mountPage();
    await fillQuery(wrapper);
    await probe(wrapper);

    expect(
      wrapper.get('[data-testid="ai-cross-source-probe-error"]').text(),
    ).toContain('ai:cross-source:integrity');
    expect(
      wrapper
        .find('[data-testid="ai-cross-source-fetch"]')
        .attributes('disabled'),
    ).toBeDefined();
  });

  it('取数失败时说明 query 权限要求', async () => {
    const wrapper = await mountPage();
    await fillQuery(wrapper);
    await probe(wrapper);
    request.get.mockRejectedValue(new Error('forbidden'));
    await fetchResult(wrapper);

    expect(
      wrapper.get('[data-testid="ai-cross-source-fetch-error"]').text(),
    ).toContain('ai:cross-source:query');
    expect(
      wrapper.find('[data-testid="ai-cross-source-amounts"]').exists(),
    ).toBe(false);
  });
});
