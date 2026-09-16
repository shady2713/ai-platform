import type { VueWrapper } from '@vue/test-utils';

import type { TabsProps } from './types';

import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useTabsDrag } from './use-tabs-drag';

const mocks = vi.hoisted(() => ({
  initializeSortable: vi.fn(),
  isMobile: { value: false },
  useIsMobile: vi.fn(),
  useSortable: vi.fn(),
}));

vi.mock('@vben-core/composables', () => ({
  useIsMobile: mocks.useIsMobile,
  useSortable: mocks.useSortable,
}));

interface DragEventFixture {
  item: HTMLElement;
  newIndex?: number;
  oldIndex?: number;
}

interface MoveEventFixture {
  dragged: HTMLElement;
  related: HTMLElement;
}

interface SortableCallbacks {
  filter: (event: Event, target: HTMLElement) => boolean;
  onEnd: (event: DragEventFixture) => void;
  onMove: (event: MoveEventFixture) => boolean;
  onStart: () => void;
}

interface SortableInstanceFixture {
  destroy: ReturnType<typeof vi.fn>;
}

let emit: ReturnType<typeof vi.fn>;
let props: TabsProps;
let sortableInstance: SortableInstanceFixture;
let wrapper: undefined | VueWrapper;

function createHarness(includeContent = true) {
  return defineComponent({
    setup() {
      const { tabsViewRef } = useTabsDrag(props, emit);
      return () =>
        h('div', { class: 'tabs-view-root', ref: tabsViewRef }, [
          includeContent
            ? h('div', { class: props.contentClass }, [
                h('div', { class: 'group draggable' }, [
                  h('span', { class: 'tab-child' }),
                ]),
                h('div', { class: 'group affix-tab draggable' }),
                h('div', { class: 'group' }),
              ])
            : undefined,
        ]);
    },
  });
}

async function mountHarness(includeContent = true) {
  wrapper = mount(createHarness(includeContent));
  await flushPromises();
  return wrapper;
}

function latestCallbacks() {
  const call = mocks.useSortable.mock.calls.at(-1);
  if (!call) throw new Error('Expected useSortable to be called');
  return call[1] as SortableCallbacks;
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.isMobile.value = false;
  mocks.useIsMobile.mockReturnValue({ isMobile: ref(false) });
  sortableInstance = { destroy: vi.fn() };
  mocks.initializeSortable.mockResolvedValue(sortableInstance);
  mocks.useSortable.mockReturnValue({
    initializeSortable: mocks.initializeSortable,
  });
  emit = vi.fn();
  props = reactive<TabsProps>({
    contentClass: 'vben-tabs-content',
    draggable: true,
    styleType: 'chrome',
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
});

describe('useTabsDrag', () => {
  it('initializes against its own view root instead of a global match', async () => {
    const decoy = document.createElement('div');
    decoy.className = 'vben-tabs-content';
    document.body.prepend(decoy);

    const mounted = await mountHarness();

    expect(mocks.useSortable).toHaveBeenCalledOnce();
    expect(mocks.useSortable.mock.calls[0]?.[0]).toBe(
      mounted.find('.vben-tabs-content').element,
    );
    expect(mocks.useSortable.mock.calls[0]?.[0]).not.toBe(decoy);
    decoy.remove();
  });

  it('filters non-draggable targets and honors the draggable switch', async () => {
    const mounted = await mountHarness();
    const callbacks = latestCallbacks();
    const child = mounted.find('.tab-child').element as HTMLElement;
    const plainTab = mounted.find('.group:not(.draggable)')
      .element as HTMLElement;

    expect(callbacks.filter(new Event('pointerdown'), child)).toBe(false);
    expect(callbacks.filter(new Event('pointerdown'), plainTab)).toBe(true);
    props.draggable = false;
    expect(callbacks.filter(new Event('pointerdown'), child)).toBe(true);
  });

  it('updates drag styling and emits only a valid index change', async () => {
    const mounted = await mountHarness();
    const callbacks = latestCallbacks();
    const container = mounted.find('.vben-tabs-content').element as HTMLElement;
    const draggable = mounted.find('.draggable').element as HTMLElement;

    callbacks.onStart();
    expect(container.style.cursor).toBe('grabbing');
    expect(draggable.classList.contains('dragging')).toBe(true);

    callbacks.onEnd({ item: draggable, newIndex: 2, oldIndex: 0 });
    expect(emit).toHaveBeenCalledWith('sortTabs', 0, 2);
    expect(container.style.cursor).toBe('default');
    expect(draggable.classList.contains('dragging')).toBe(false);

    callbacks.onEnd({ item: draggable, newIndex: 1, oldIndex: 1 });
    callbacks.onEnd({ item: draggable, newIndex: Number.NaN, oldIndex: 0 });
    callbacks.onEnd({ item: draggable, oldIndex: 0 });
    const plainTab = mounted.find('.group:not(.draggable)')
      .element as HTMLElement;
    callbacks.onEnd({ item: plainTab, newIndex: 2, oldIndex: 0 });
    callbacks.onEnd({
      item: document.createElement('div'),
      newIndex: 2,
      oldIndex: 0,
    });
    expect(emit).toHaveBeenCalledOnce();
  });

  it('allows moves only between draggable tabs with the same affix state', async () => {
    const mounted = await mountHarness();
    const callbacks = latestCallbacks();
    const regular = mounted.find('.group.draggable').element as HTMLElement;
    const affix = mounted.find('.affix-tab').element as HTMLElement;
    const plain = mounted.find('.group:not(.draggable)').element as HTMLElement;

    expect(callbacks.onMove({ dragged: regular, related: regular })).toBe(true);
    expect(callbacks.onMove({ dragged: affix, related: affix })).toBe(true);
    expect(callbacks.onMove({ dragged: regular, related: affix })).toBe(false);
    expect(callbacks.onMove({ dragged: regular, related: plain })).toBe(false);
    props.draggable = false;
    expect(callbacks.onMove({ dragged: regular, related: regular })).toBe(
      false,
    );
  });

  it('reinitializes on style changes and destroys the prior instance', async () => {
    await mountHarness();
    const firstInstance = sortableInstance;
    const secondInstance = { destroy: vi.fn() };
    mocks.initializeSortable.mockResolvedValueOnce(secondInstance);

    props.styleType = 'plain';
    await flushPromises();

    expect(firstInstance.destroy).toHaveBeenCalledOnce();
    expect(mocks.useSortable).toHaveBeenCalledTimes(2);
    wrapper?.unmount();
    wrapper = undefined;
    expect(secondInstance.destroy).toHaveBeenCalledOnce();
  });

  it('destroys a stale asynchronous instance after a newer initialization', async () => {
    let resolveFirst: ((value: SortableInstanceFixture) => void) | undefined;
    const firstPromise = new Promise<SortableInstanceFixture>((resolve) => {
      resolveFirst = resolve;
    });
    const staleInstance = { destroy: vi.fn() };
    const currentInstance = { destroy: vi.fn() };
    mocks.initializeSortable
      .mockReturnValueOnce(firstPromise)
      .mockResolvedValueOnce(currentInstance);

    wrapper = mount(createHarness());
    await Promise.resolve();
    props.styleType = 'plain';
    await flushPromises();
    resolveFirst?.(staleInstance);
    await flushPromises();

    expect(staleInstance.destroy).toHaveBeenCalledOnce();
    expect(currentInstance.destroy).not.toHaveBeenCalled();
  });

  it('does not initialize on mobile or without a content element', async () => {
    mocks.useIsMobile.mockReturnValue({ isMobile: ref(true) });
    const mobileWrapper = await mountHarness();
    expect(mocks.useSortable).not.toHaveBeenCalled();

    mobileWrapper.unmount();
    wrapper = undefined;
    mocks.useIsMobile.mockReturnValue({ isMobile: ref(false) });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const noContentWrapper = await mountHarness(false);
    expect(mocks.useSortable).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledWith(
      'Element not found for sortable initialization',
    );

    noContentWrapper.unmount();
    wrapper = undefined;
    props.contentClass = undefined;
    await mountHarness();
    expect(mocks.useSortable).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledTimes(2);
    warn.mockRestore();
  });
});
