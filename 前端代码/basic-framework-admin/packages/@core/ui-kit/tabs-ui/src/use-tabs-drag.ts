import type { Sortable } from '@vben-core/composables';

import type { TabsProps } from './types';

import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';

import { useIsMobile, useSortable } from '@vben-core/composables';

type TabsDragEmit = (
  event: 'sortTabs',
  oldIndex: number,
  newIndex: number,
) => void;

// Sortable events may originate from a tab's child element.
function findParentElement(element: HTMLElement) {
  const parentCls = 'group';
  return element.classList.contains(parentCls)
    ? element
    : element.closest(`.${parentCls}`);
}

export function useTabsDrag(props: TabsProps, emit: TabsDragEmit) {
  const sortableInstance = ref<null | Sortable>(null);
  const tabsViewRef = ref<HTMLElement>();
  let initializationGeneration = 0;

  function destroySortable() {
    initializationGeneration += 1;
    sortableInstance.value?.destroy();
    sortableInstance.value = null;
  }

  async function initTabsSortable(generation: number) {
    await nextTick();

    if (generation !== initializationGeneration) return;

    const contentClass = props.contentClass;
    const el = contentClass
      ? tabsViewRef.value?.getElementsByClassName(contentClass)[0]
      : undefined;

    if (!(el instanceof HTMLElement)) {
      console.warn('Element not found for sortable initialization');
      return;
    }

    const resetElState = () => {
      el.style.cursor = 'default';
      el.querySelector('.draggable')?.classList.remove('dragging');
    };

    const { initializeSortable } = useSortable(el, {
      filter: (_evt, target: HTMLElement) => {
        const parent = findParentElement(target);
        const draggable = parent?.classList.contains('draggable');
        return !draggable || !props.draggable;
      },
      onEnd(evt) {
        const { newIndex, oldIndex } = evt;
        const srcParent = findParentElement(evt.item);

        if (!srcParent) {
          resetElState();
          return;
        }

        if (!srcParent.classList.contains('draggable')) {
          resetElState();

          return;
        }

        if (
          oldIndex !== undefined &&
          newIndex !== undefined &&
          !Number.isNaN(oldIndex) &&
          !Number.isNaN(newIndex) &&
          oldIndex !== newIndex
        ) {
          emit('sortTabs', oldIndex, newIndex);
        }
        resetElState();
      },
      onMove(evt) {
        const parent = findParentElement(evt.related);
        if (parent?.classList.contains('draggable') && props.draggable) {
          const isCurrentAffix = evt.dragged.classList.contains('affix-tab');
          const isRelatedAffix = evt.related.classList.contains('affix-tab');
          // 不允许在固定的tab和非固定的tab之间互相拖拽
          return isCurrentAffix === isRelatedAffix;
        } else {
          return false;
        }
      },
      onStart: () => {
        el.style.cursor = 'grabbing';
        el.querySelector('.draggable')?.classList.add('dragging');
      },
    });

    const nextInstance = await initializeSortable();
    if (generation !== initializationGeneration) {
      nextInstance?.destroy();
      return;
    }

    sortableInstance.value = nextInstance;
  }

  async function init() {
    const generation = ++initializationGeneration;
    const { isMobile } = useIsMobile();

    if (isMobile.value) return;

    await initTabsSortable(generation);
  }

  onMounted(() => void init());

  watch(
    () => props.styleType,
    () => {
      destroySortable();
      void init();
    },
  );

  onUnmounted(destroySortable);

  return { tabsViewRef };
}
