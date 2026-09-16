import type { VueWrapper } from '@vue/test-utils';

import type { TabDefinition } from '@vben-core/typings';

import type { TabsProps } from './types';

import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useTabsViewScroll } from './use-tabs-view-scroll';

class MockResizeObserver {
  static instances: MockResizeObserver[] = [];

  readonly disconnect = vi.fn();
  readonly observe = vi.fn();

  constructor(readonly callback: (entries: ResizeObserverEntry[]) => void) {
    MockResizeObserver.instances.push(this);
  }

  trigger() {
    this.callback([]);
  }
}

class MockMutationObserver {
  static instances: MockMutationObserver[] = [];

  readonly disconnect = vi.fn();
  readonly observe = vi.fn();

  constructor(readonly callback: MutationCallback) {
    MockMutationObserver.instances.push(this);
  }

  trigger() {
    this.callback([], this as unknown as MutationObserver);
  }
}

interface ScrollFixture {
  activeItem: HTMLDivElement;
  root: HTMLDivElement;
  scrollBy: ReturnType<typeof vi.fn>;
  scrollIntoView: ReturnType<typeof vi.fn>;
  setSizes: (clientWidth: number, scrollWidth: number) => void;
  viewport: HTMLDivElement;
}

function createTab(path: string): TabDefinition {
  return {
    fullPath: path,
    hash: '',
    key: path,
    matched: [],
    meta: { title: path },
    name: path,
    params: {},
    path,
    query: {},
    redirectedFrom: undefined,
  };
}

function createScrollFixture(
  initialClientWidth = 100,
  initialScrollWidth = 300,
): ScrollFixture {
  let clientWidth = initialClientWidth;
  let scrollWidth = initialScrollWidth;
  const root = document.createElement('div');
  const viewport = document.createElement('div');
  const activeItem = document.createElement('div');
  const tabItem = document.createElement('div');
  const scrollBy = vi.fn();
  const scrollIntoView = vi.fn();
  viewport.dataset.rekaScrollAreaViewport = '';
  tabItem.dataset.tabItem = 'true';
  activeItem.className = 'is-active';
  activeItem.scrollIntoView = scrollIntoView;
  viewport.scrollBy = scrollBy;
  viewport.append(tabItem, activeItem);
  root.append(viewport);
  Object.defineProperties(viewport, {
    clientWidth: { configurable: true, get: () => clientWidth },
    scrollWidth: { configurable: true, get: () => scrollWidth },
  });
  return {
    activeItem,
    root,
    scrollBy,
    scrollIntoView,
    setSizes(nextClientWidth, nextScrollWidth) {
      clientWidth = nextClientWidth;
      scrollWidth = nextScrollWidth;
    },
    viewport,
  };
}

function latestResizeObserver() {
  const observer = MockResizeObserver.instances.at(-1);
  if (!observer) {
    throw new Error('Expected ResizeObserver to be created');
  }
  return observer;
}

function latestMutationObserver() {
  const observer = MockMutationObserver.instances.at(-1);
  if (!observer) {
    throw new Error('Expected MutationObserver to be created');
  }
  return observer;
}

function mountHarness(initialProps: TabsProps = {}) {
  const props = reactive<TabsProps>(initialProps);
  let controls: ReturnType<typeof useTabsViewScroll> | undefined;
  const Harness = defineComponent({
    setup() {
      controls = useTabsViewScroll(props);
      return () => h('div');
    },
  });
  const wrapper = mount(Harness);
  if (!controls) {
    throw new Error('Expected scroll controls to be initialized');
  }
  return { controls, props, wrapper } as {
    controls: ReturnType<typeof useTabsViewScroll>;
    props: TabsProps;
    wrapper: VueWrapper;
  };
}

describe('useTabsViewScroll', () => {
  beforeEach(() => {
    MockResizeObserver.instances = [];
    MockMutationObserver.instances = [];
    vi.stubGlobal('ResizeObserver', MockResizeObserver);
    vi.stubGlobal('MutationObserver', MockMutationObserver);
    vi.stubGlobal(
      'requestAnimationFrame',
      vi.fn((callback: FrameRequestCallback) => {
        callback(0);
        return 1;
      }),
    );
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('initializes overflow state, observers, and the active item', async () => {
    const { controls } = mountHarness({ tabs: [createTab('/one')] });
    const fixture = createScrollFixture();
    controls.scrollbarRef.value = { $el: fixture.root };

    await controls.initScrollbar();

    expect(controls.showScrollButton.value).toBe(true);
    expect(fixture.scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      inline: 'start',
    });
    expect(latestResizeObserver().observe).toHaveBeenCalledWith(
      fixture.viewport,
    );
    expect(latestMutationObserver().observe).toHaveBeenCalledWith(
      fixture.viewport,
      {
        attributes: false,
        childList: true,
        subtree: true,
      },
    );
  });

  it('scrolls in the requested direction on narrow and wide viewports', async () => {
    const { controls } = mountHarness();
    const fixture = createScrollFixture(100, 300);
    controls.scrollbarRef.value = { $el: fixture.root };
    await controls.initScrollbar();
    fixture.scrollBy.mockClear();

    controls.scrollDirection('right');
    controls.scrollDirection('left');
    fixture.setSizes(500, 900);
    controls.scrollDirection('right', 150);

    expect(fixture.scrollBy).toHaveBeenNthCalledWith(1, {
      behavior: 'smooth',
      left: 100,
    });
    expect(fixture.scrollBy).toHaveBeenNthCalledWith(2, {
      behavior: 'smooth',
      left: -100,
    });
    expect(fixture.scrollBy).toHaveBeenNthCalledWith(3, {
      behavior: 'smooth',
      left: 350,
    });
  });

  it('does not scroll without a measurable overflowing viewport', async () => {
    const { controls } = mountHarness();
    controls.scrollDirection('right');
    const fixture = createScrollFixture(100, 100);
    controls.scrollbarRef.value = { $el: fixture.root };
    await controls.initScrollbar();

    controls.scrollDirection('right');
    fixture.setSizes(0, 300);
    controls.scrollDirection('right');

    expect(controls.showScrollButton.value).toBe(false);
    expect(fixture.scrollBy).not.toHaveBeenCalled();
  });

  it('uses the dominant wheel axis and debounces edge state updates', async () => {
    vi.useFakeTimers();
    const { controls } = mountHarness();
    const fixture = createScrollFixture();
    controls.scrollbarRef.value = { $el: fixture.root };
    await controls.initScrollbar();
    fixture.scrollBy.mockClear();

    controls.handleWheel(new WheelEvent('wheel', { deltaX: 5, deltaY: 2 }));
    controls.handleWheel(new WheelEvent('wheel', { deltaX: 1, deltaY: -4 }));
    controls.handleScrollAt({ left: false, right: true });
    expect(controls.scrollIsAtLeft.value).toBe(true);
    await vi.advanceTimersByTimeAsync(100);

    expect(fixture.scrollBy).toHaveBeenNthCalledWith(1, { left: 15 });
    expect(fixture.scrollBy).toHaveBeenNthCalledWith(2, { left: -12 });
    expect(controls.scrollIsAtLeft.value).toBe(false);
    expect(controls.scrollIsAtRight.value).toBe(true);
  });

  it('reacts to item mutations, resize, active, and style changes', async () => {
    vi.useFakeTimers();
    const { controls, props } = mountHarness({
      active: '/one',
      styleType: 'chrome',
      tabs: [createTab('/one')],
    });
    const fixture = createScrollFixture();
    controls.scrollbarRef.value = { $el: fixture.root };
    await controls.initScrollbar();
    await vi.runAllTimersAsync();
    const initialResizeObserver = latestResizeObserver();
    const initialMutationObserver = latestMutationObserver();
    fixture.scrollIntoView.mockClear();

    const newTab = document.createElement('div');
    newTab.dataset.tabItem = 'true';
    fixture.viewport.append(newTab);
    initialMutationObserver.trigger();
    await nextTick();
    await vi.runAllTimersAsync();
    expect(fixture.scrollIntoView).toHaveBeenCalledOnce();

    fixture.scrollIntoView.mockClear();
    initialResizeObserver.trigger();
    await vi.advanceTimersByTimeAsync(100);
    await vi.runAllTimersAsync();
    expect(fixture.scrollIntoView).toHaveBeenCalledOnce();

    fixture.scrollIntoView.mockClear();
    props.active = '/two';
    await nextTick();
    await vi.runAllTimersAsync();
    expect(fixture.scrollIntoView).toHaveBeenCalledOnce();

    props.styleType = 'plain';
    await nextTick();
    await nextTick();
    expect(initialResizeObserver.disconnect).toHaveBeenCalledOnce();
    expect(initialMutationObserver.disconnect).toHaveBeenCalledOnce();
  });

  it('ignores stale initialization and disconnects observers on unmount', async () => {
    vi.useFakeTimers();
    const { controls, props, wrapper } = mountHarness({ active: '/one' });
    await nextTick();
    props.active = '/two';
    await nextTick();
    const emptyRoot = document.createElement('div');
    controls.scrollbarRef.value = { $el: emptyRoot };
    await controls.initScrollbar();
    expect(MockResizeObserver.instances).toHaveLength(0);

    const fixture = createScrollFixture();
    controls.scrollbarRef.value = { $el: fixture.root };
    const firstInitialization = controls.initScrollbar();
    const secondInitialization = controls.initScrollbar();
    await Promise.all([firstInitialization, secondInitialization]);
    expect(MockResizeObserver.instances).toHaveLength(1);
    const resizeObserver = latestResizeObserver();
    const mutationObserver = latestMutationObserver();

    wrapper.unmount();
    resizeObserver.trigger();
    mutationObserver.trigger();
    await vi.runAllTimersAsync();

    expect(resizeObserver.disconnect).toHaveBeenCalledOnce();
    expect(mutationObserver.disconnect).toHaveBeenCalledOnce();
  });
});
