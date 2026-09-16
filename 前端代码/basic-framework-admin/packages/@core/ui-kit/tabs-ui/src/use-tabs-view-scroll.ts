import type { Ref } from 'vue';

import type { TabsProps } from './types';

import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';

import { useDebounceFn } from '@vueuse/core';

type DomElement = Element | null | undefined;

interface ScrollAtState {
  left: boolean;
  right: boolean;
}

interface ScrollbarInstance {
  $el: Element;
}

interface TabsViewScrollControls {
  handleScrollAt: (state: ScrollAtState) => void;
  handleWheel: (event: WheelEvent) => void;
  initScrollbar: () => Promise<void>;
  scrollbarRef: Ref<null | ScrollbarInstance>;
  scrollDirection: (direction: 'left' | 'right', distance?: number) => void;
  scrollIsAtLeft: Ref<boolean>;
  scrollIsAtRight: Ref<boolean>;
  showScrollButton: Ref<boolean>;
}

export function useTabsViewScroll(props: TabsProps): TabsViewScrollControls {
  let resizeObserver: null | ResizeObserver = null;
  let mutationObserver: MutationObserver | null = null;
  let observerGeneration = 0;
  let tabItemCount = 0;
  const scrollbarRef = ref<null | ScrollbarInstance>(null);
  const scrollViewportEl = ref<DomElement>(null);
  const showScrollButton = ref(false);
  const scrollIsAtLeft = ref(true);
  const scrollIsAtRight = ref(false);

  function disconnectObservers() {
    observerGeneration += 1;
    resizeObserver?.disconnect();
    mutationObserver?.disconnect();
    resizeObserver = null;
    mutationObserver = null;
    scrollViewportEl.value = null;
    return observerGeneration;
  }

  function scrollDirection(
    direction: 'left' | 'right',
    distance: number = 150,
  ) {
    const viewportEl = scrollViewportEl.value;
    const viewportWidth = viewportEl?.clientWidth ?? 0;
    if (
      !viewportEl ||
      !viewportWidth ||
      viewportEl.scrollWidth <= viewportWidth
    ) {
      return;
    }
    const overlap = Math.max(0, distance);
    const scrollDistance =
      viewportWidth > overlap ? viewportWidth - overlap : viewportWidth;

    viewportEl.scrollBy({
      behavior: 'smooth',
      left: direction === 'left' ? -scrollDistance : scrollDistance,
    });
  }

  async function initScrollbar() {
    const generation = disconnectObservers();
    await nextTick();
    if (generation !== observerGeneration) {
      return;
    }

    const scrollbarEl = scrollbarRef.value?.$el;
    if (!scrollbarEl) {
      return;
    }

    const viewportEl = scrollbarEl?.querySelector(
      'div[data-reka-scroll-area-viewport]',
    );
    if (!viewportEl) {
      return;
    }

    scrollViewportEl.value = viewportEl;
    calcShowScrollbarButton();

    await nextTick();
    await scrollToActiveIntoView();

    resizeObserver = new ResizeObserver(
      useDebounceFn((_entries: ResizeObserverEntry[]) => {
        if (generation !== observerGeneration) {
          return;
        }
        calcShowScrollbarButton();
        void scrollToActiveIntoView();
      }, 100),
    );
    resizeObserver.observe(viewportEl);

    tabItemCount = props.tabs?.length || 0;
    mutationObserver = new MutationObserver(() => {
      const count = viewportEl.querySelectorAll(
        `div[data-tab-item="true"]`,
      ).length;

      if (count > tabItemCount) {
        void scrollToActiveIntoView();
      }

      if (count !== tabItemCount) {
        calcShowScrollbarButton();
        tabItemCount = count;
      }
    });

    mutationObserver.observe(viewportEl, {
      attributes: false,
      childList: true,
      subtree: true,
    });
  }

  async function scrollToActiveIntoView() {
    if (!scrollViewportEl.value) {
      return;
    }
    await nextTick();
    const viewportEl = scrollViewportEl.value;
    const viewportWidth = viewportEl.clientWidth;
    const { scrollWidth } = viewportEl;

    if (viewportWidth >= scrollWidth) {
      return;
    }

    requestAnimationFrame(() => {
      const activeItem = viewportEl?.querySelector('.is-active');
      activeItem?.scrollIntoView({ behavior: 'smooth', inline: 'start' });
    });
  }

  /**
   * 计算tabs 宽度，用于判断是否显示左右滚动按钮
   */
  function calcShowScrollbarButton() {
    const viewportEl = scrollViewportEl.value;
    if (!viewportEl) {
      return;
    }

    showScrollButton.value = viewportEl.scrollWidth > viewportEl.clientWidth;
  }

  const handleScrollAt = useDebounceFn(({ left, right }: ScrollAtState) => {
    scrollIsAtLeft.value = left;
    scrollIsAtRight.value = right;
  }, 100);

  function handleWheel({ deltaX, deltaY }: WheelEvent) {
    const delta = Math.abs(deltaX) > Math.abs(deltaY) ? deltaX : deltaY;
    scrollViewportEl.value?.scrollBy({
      left: delta * 3,
    });
  }

  watch(
    () => props.active,
    async () => {
      await scrollToActiveIntoView();
    },
    {
      flush: 'post',
    },
  );

  watch(
    () => props.styleType,
    () => {
      void initScrollbar();
    },
  );

  onMounted(() => void initScrollbar());

  onUnmounted(() => {
    disconnectObservers();
  });

  return {
    handleScrollAt,
    handleWheel,
    initScrollbar,
    scrollbarRef,
    scrollDirection,
    scrollIsAtLeft,
    scrollIsAtRight,
    showScrollButton,
  };
}
