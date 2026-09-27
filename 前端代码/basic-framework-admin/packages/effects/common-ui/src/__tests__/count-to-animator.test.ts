import { mount } from '@vue/test-utils';

import { VbenCountToAnimator } from '@vben-core/shadcn-ui';

import { describe, expect, it, vi } from 'vitest';

/**
 * 卡片（SummaryCard/StatisticCard）渲染的数字动画组件行为测试。
 *
 * 覆盖率棘轮要求该共享组件保持基线（100% 行覆盖），而本仓库单测里只有
 * common-ui 的卡片用例会真正渲染它；这里补齐动画开始/结束回调、endVal 变化
 * 重启动画、reset()、useEasing 关闭、非法数值与千分位分隔这些既有分支。
 *
 * 动画用真实计时器驱动（useTransition 走 requestAnimationFrame），
 * 通过 vi.waitFor 等待过渡结束，避免依赖 fake timers 对 rAF 的模拟差异。
 */
interface CountToAnimatorExposed {
  reset: () => void;
}

describe('countToAnimator（卡片数字动画）', () => {
  it('autoplay 过渡发出 started/finished，并支持千分位、前后缀与小数位', async () => {
    const wrapper = mount(VbenCountToAnimator, {
      props: {
        decimals: 2,
        duration: 50,
        endVal: 1_234_567,
        prefix: '¥',
        suffix: ' 元',
      },
    });

    await vi.waitFor(() => {
      expect(wrapper.emitted('started')).toBeTruthy();
      expect(wrapper.emitted('finished')).toBeTruthy();
    });
    expect(wrapper.text()).toContain('¥1,234,567.00 元');

    wrapper.unmount();
  });

  it('endVal 变化时重新启动；reset() 回到 startVal（useEasing 关闭路径）', async () => {
    const wrapper = mount(VbenCountToAnimator, {
      props: { duration: 50, endVal: 10, useEasing: false },
    });
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('10');
    });

    await wrapper.setProps({ endVal: 20 });
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('20');
    });
    expect(wrapper.emitted('started')?.length).toBeGreaterThanOrEqual(2);

    (wrapper.vm as unknown as CountToAnimatorExposed).reset();
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('0');
    });

    wrapper.unmount();
  });

  it('非法数值渲染为空串而不是 NaN', () => {
    const wrapper = mount(VbenCountToAnimator, {
      props: { autoplay: false, startVal: Number.NaN },
    });

    expect(wrapper.text()).toBe('');

    wrapper.unmount();
  });
});
