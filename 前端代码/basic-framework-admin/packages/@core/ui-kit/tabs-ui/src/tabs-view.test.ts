import type { VueWrapper } from '@vue/test-utils';

import { mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import TabsView from './tabs-view.vue';

const mocks = vi.hoisted(() => ({
  handleScrollAt: vi.fn(),
  handleWheel: vi.fn(),
  scrollDirection: vi.fn(),
  useTabsDrag: vi.fn(),
  useTabsViewScroll: vi.fn(),
}));

vi.mock('@vben-core/composables', () => ({
  useForwardPropsEmits: () => ({ forwarded: true }),
}));

vi.mock('@vben-core/icons', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    ChevronsLeft: defineComponent(() => () => h('i', { class: 'left-icon' })),
    ChevronsRight: defineComponent(() => () => h('i', { class: 'right-icon' })),
  };
});

vi.mock('@vben-core/shadcn-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    VbenScrollbar: defineComponent({
      name: 'VbenScrollbar',
      emits: ['scroll-at', 'wheel'],
      setup(_props, { slots }) {
        return () => h('div', { class: 'scrollbar' }, slots.default?.());
      },
    }),
  };
});

vi.mock('./components', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Tabs: defineComponent({
      name: 'TabItems',
      setup: () => () => h('div', { class: 'plain-tabs' }),
    }),
    TabsChrome: defineComponent({
      name: 'TabsChrome',
      setup: () => () => h('div', { class: 'chrome-tabs' }),
    }),
  };
});

vi.mock('./use-tabs-drag', () => ({
  useTabsDrag: mocks.useTabsDrag,
}));

vi.mock('./use-tabs-view-scroll', () => ({
  useTabsViewScroll: mocks.useTabsViewScroll,
}));

interface ScrollState {
  scrollIsAtLeft: ReturnType<typeof ref<boolean>>;
  scrollIsAtRight: ReturnType<typeof ref<boolean>>;
  showScrollButton: ReturnType<typeof ref<boolean>>;
}

let scrollState: ScrollState;

function mountTabsView(props: Record<string, unknown> = {}): VueWrapper {
  return mount(TabsView, { props });
}

function emitWheel(wrapper: VueWrapper, deltaX: number, deltaY: number) {
  const event = new WheelEvent('wheel', {
    cancelable: true,
    deltaX,
    deltaY,
  });
  const stopPropagation = vi.spyOn(event, 'stopPropagation');
  wrapper.findComponent({ name: 'VbenScrollbar' }).vm.$emit('wheel', event);
  return { event, stopPropagation };
}

beforeEach(() => {
  vi.clearAllMocks();
  scrollState = {
    scrollIsAtLeft: ref(true),
    scrollIsAtRight: ref(true),
    showScrollButton: ref(false),
  };
  mocks.useTabsViewScroll.mockReturnValue({
    ...scrollState,
    handleScrollAt: mocks.handleScrollAt,
    handleWheel: mocks.handleWheel,
    scrollbarRef: ref(),
    scrollDirection: mocks.scrollDirection,
  });
  mocks.useTabsDrag.mockReturnValue({ tabsViewRef: ref() });
});

describe('tabs view', () => {
  it('renders the default chrome style and wires both composables', () => {
    const wrapper = mountTabsView();

    expect(wrapper.find('.chrome-tabs').exists()).toBe(true);
    expect(wrapper.find('.plain-tabs').exists()).toBe(false);
    expect(
      wrapper.findAll('div').some((element) => element.classes('pt-[3px]')),
    ).toBe(true);
    expect(wrapper.findAll('button')).toHaveLength(2);
    expect(
      wrapper
        .findAll('button')
        .every((button) =>
          button.attributes('style')?.includes('display: none'),
        ),
    ).toBe(true);
    expect(mocks.useTabsViewScroll).toHaveBeenCalledOnce();
    expect(mocks.useTabsDrag).toHaveBeenCalledOnce();
  });

  it('renders the plain style without chrome spacing', () => {
    const wrapper = mountTabsView({ styleType: 'plain' });

    expect(wrapper.find('.plain-tabs').exists()).toBe(true);
    expect(wrapper.find('.chrome-tabs').exists()).toBe(false);
    expect(
      wrapper.findAll('div').some((element) => element.classes('pt-[3px]')),
    ).toBe(false);
  });

  it('exposes semantic disabled buttons and only invokes enabled directions', async () => {
    scrollState.showScrollButton.value = true;
    scrollState.scrollIsAtRight.value = false;
    const wrapper = mountTabsView();
    const [leftButton, rightButton] = wrapper.findAll('button');

    expect(leftButton?.attributes('aria-label')).toBe('Scroll tabs left');
    expect(leftButton?.attributes('disabled')).toBeDefined();
    expect(rightButton?.attributes('aria-label')).toBe('Scroll tabs right');
    expect(rightButton?.attributes('disabled')).toBeUndefined();

    await leftButton?.trigger('click');
    await rightButton?.trigger('click');
    expect(mocks.scrollDirection).toHaveBeenCalledOnce();
    expect(mocks.scrollDirection).toHaveBeenCalledWith('right');

    scrollState.scrollIsAtLeft.value = false;
    scrollState.scrollIsAtRight.value = true;
    await nextTick();
    await leftButton?.trigger('click');
    expect(mocks.scrollDirection).toHaveBeenLastCalledWith('left');
  });

  it('forwards scrollbar boundary events', () => {
    const wrapper = mountTabsView();
    wrapper.findComponent({ name: 'VbenScrollbar' }).vm.$emit('scroll-at', {
      left: true,
      right: false,
    });

    expect(mocks.handleScrollAt).toHaveBeenCalledWith({
      left: true,
      right: false,
    });
  });

  it('consumes dominant wheel movement only while overflow can move', () => {
    scrollState.showScrollButton.value = true;
    scrollState.scrollIsAtLeft.value = false;
    scrollState.scrollIsAtRight.value = false;
    const wrapper = mountTabsView();
    const { event, stopPropagation } = emitWheel(wrapper, 8, 40);

    expect(mocks.handleWheel).toHaveBeenCalledWith(event);
    expect(stopPropagation).toHaveBeenCalledOnce();
    expect(event.defaultPrevented).toBe(true);

    mocks.handleWheel.mockClear();
    emitWheel(wrapper, -50, 10);
    expect(mocks.handleWheel).toHaveBeenCalledOnce();
  });

  it.each([
    [
      'wheel support is disabled',
      { wheelable: false },
      true,
      false,
      false,
      0,
      40,
    ],
    ['tabs do not overflow', {}, false, false, false, 0, 40],
    ['the right edge is reached', {}, true, false, true, 0, 40],
    ['the left edge is reached', {}, true, true, false, 0, -40],
    ['the wheel has no movement', {}, true, false, false, 0, 0],
  ])(
    'leaves page scrolling untouched when %s',
    (_label, props, overflow, atLeft, atRight, deltaX, deltaY) => {
      scrollState.showScrollButton.value = overflow;
      scrollState.scrollIsAtLeft.value = atLeft;
      scrollState.scrollIsAtRight.value = atRight;
      const wrapper = mountTabsView(props);
      const { event, stopPropagation } = emitWheel(wrapper, deltaX, deltaY);

      expect(mocks.handleWheel).not.toHaveBeenCalled();
      expect(stopPropagation).not.toHaveBeenCalled();
      expect(event.defaultPrevented).toBe(false);
    },
  );
});
