import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import VbenScrollbar from './scrollbar.vue';

function scrollableElement(overrides: Record<string, number>) {
  const element = document.createElement('div');
  Object.defineProperty(element, 'scrollTop', {
    configurable: true,
    value: overrides.scrollTop ?? 0,
  });
  Object.defineProperty(element, 'scrollLeft', {
    configurable: true,
    value: overrides.scrollLeft ?? 0,
  });
  Object.defineProperty(element, 'clientHeight', {
    configurable: true,
    value: overrides.clientHeight ?? 0,
  });
  Object.defineProperty(element, 'clientWidth', {
    configurable: true,
    value: overrides.clientWidth ?? 0,
  });
  Object.defineProperty(element, 'scrollHeight', {
    configurable: true,
    value: overrides.scrollHeight ?? 0,
  });
  Object.defineProperty(element, 'scrollWidth', {
    configurable: true,
    value: overrides.scrollWidth ?? 0,
  });
  return element;
}

function mountScrollbar(props: Record<string, unknown> = {}) {
  return mount(VbenScrollbar, {
    props: { ...props },
    slots: { default: '<div class="content">payload</div>' },
    global: {
      stubs: {
        ScrollAreaRoot: { template: '<div class="root"><slot /></div>' },
        ScrollAreaViewport: {
          inheritAttrs: false,
          template:
            '<div class="viewport" @scroll="$attrs.onScroll"><slot /></div>',
        },
        ScrollAreaCorner: true,
        ScrollBar: {
          template:
            '<div class="scrollbar-stub" :data-orientation="$attrs.orientation"><slot /></div>',
        },
      },
    },
  });
}

describe('vbenScrollbar', () => {
  it('renders content and emits the arrived state on scroll', async () => {
    const wrapper = mountScrollbar({ shadow: true });
    const viewport = wrapper.get('.viewport');
    viewport.element.append(scrollableElement({}));

    // 未滚动：全零尺寸下 top/left 到达，bottom/right 处于边界（0 >= -阈值）
    viewport.element.dispatchEvent(new Event('scroll'));
    expect(wrapper.emitted('scrollAt')?.at(-1)).toEqual([
      { bottom: true, left: true, right: true, top: true },
    ]);

    // 滚到底部与右侧
    viewport.element.append(
      scrollableElement({
        scrollTop: 100,
        scrollLeft: 40,
        clientHeight: 100,
        clientWidth: 60,
        scrollHeight: 200,
        scrollWidth: 100,
      }),
    );
    const scroller = viewport.element.lastChild as HTMLElement;
    scroller.dispatchEvent(new Event('scroll', { bubbles: true }));
    expect(wrapper.emitted('scrollAt')?.at(-1)).toEqual([
      { bottom: true, left: false, right: true, top: false },
    ]);
    expect(wrapper.text()).toContain('payload');
  });

  it('applies shadow classes when both sides are scrolled past', async () => {
    const wrapper = mountScrollbar({
      shadow: true,
      shadowLeft: true,
      shadowRight: true,
    });
    const viewport = wrapper.get('.viewport');
    viewport.element.append(
      scrollableElement({
        scrollLeft: 20,
        clientWidth: 60,
        scrollWidth: 100,
      }),
    );
    const scroller = viewport.element.lastChild as HTMLElement;
    scroller.dispatchEvent(new Event('scroll', { bubbles: true }));
    await wrapper.vm.$nextTick();

    expect(wrapper.get('.root').classes()).toContain('both-shadow');
  });

  it('renders shadow markers and the horizontal scroll bar on demand', () => {
    const wrapper = mountScrollbar({
      horizontal: true,
      scrollBarClass: 'custom-scroll-bar',
      shadow: true,
      shadowBorder: true,
      shadowTop: true,
      shadowBottom: true,
    });

    expect(wrapper.find('.scrollbar-top-shadow').exists()).toBe(true);
    expect(wrapper.find('.scrollbar-bottom-shadow').exists()).toBe(true);
    expect(wrapper.find('[data-orientation="horizontal"]').exists()).toBe(true);
  });

  it('hides shadow markers when shadow is disabled', () => {
    const wrapper = mountScrollbar({});

    expect(wrapper.find('.scrollbar-top-shadow').exists()).toBe(false);
    expect(wrapper.find('.scrollbar-bottom-shadow').exists()).toBe(false);
  });
});
