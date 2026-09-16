<script setup lang="ts">
import type { TabsEmits, TabsProps } from './types';

import { useForwardPropsEmits } from '@vben-core/composables';
import { ChevronsLeft, ChevronsRight } from '@vben-core/icons';
import { VbenScrollbar } from '@vben-core/shadcn-ui';

import { Tabs, TabsChrome } from './components';
import { useTabsDrag } from './use-tabs-drag';
import { useTabsViewScroll } from './use-tabs-view-scroll';

type Props = TabsProps;

defineOptions({
  name: 'TabsView',
});

const props = withDefaults(defineProps<Props>(), {
  contentClass: 'vben-tabs-content',
  draggable: true,
  styleType: 'chrome',
  wheelable: true,
});

const emit = defineEmits<TabsEmits>();

const forward = useForwardPropsEmits(props, emit);

const {
  handleScrollAt,
  handleWheel,
  // @ts-expect-error scrollbarRef 仅由 SFC 模板绑定
  scrollbarRef,
  scrollDirection,
  scrollIsAtLeft,
  scrollIsAtRight,
  showScrollButton,
} = useTabsViewScroll(props);

function onWheel(e: WheelEvent) {
  const delta = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
  const canScroll =
    delta < 0 ? !scrollIsAtLeft.value : delta > 0 && !scrollIsAtRight.value;

  if (!props.wheelable || !showScrollButton.value || !canScroll) return;

  handleWheel(e);
  e.stopPropagation();
  e.preventDefault();
}

// @ts-expect-error tabsViewRef 仅由 SFC 模板绑定
const { tabsViewRef } = useTabsDrag(props, emit);
</script>

<template>
  <div ref="tabsViewRef" class="flex h-full flex-1 overflow-hidden">
    <!-- 左侧滚动按钮 -->
    <button
      v-show="showScrollButton"
      :class="{
        'cursor-pointer text-muted-foreground hover:bg-muted': !scrollIsAtLeft,
        'cursor-default opacity-30': scrollIsAtLeft,
      }"
      :disabled="scrollIsAtLeft"
      aria-label="Scroll tabs left"
      class="border-r px-2"
      type="button"
      @click="scrollDirection('left')"
    >
      <ChevronsLeft aria-hidden="true" class="size-4 h-full" />
    </button>

    <div
      :class="{
        'pt-[3px]': styleType === 'chrome',
      }"
      class="size-full flex-1 overflow-hidden"
    >
      <VbenScrollbar
        ref="scrollbarRef"
        :shadow-bottom="false"
        :shadow-top="false"
        class="h-full"
        horizontal
        scroll-bar-class="z-10 hidden "
        shadow
        shadow-left
        shadow-right
        @scroll-at="handleScrollAt"
        @wheel="onWheel"
      >
        <TabsChrome
          v-if="styleType === 'chrome'"
          v-bind="{ ...forward, ...$attrs, ...$props }"
        />

        <Tabs v-else v-bind="{ ...forward, ...$attrs, ...$props }" />
      </VbenScrollbar>
    </div>

    <!-- 右侧滚动按钮 -->
    <button
      v-show="showScrollButton"
      :class="{
        'cursor-pointer text-muted-foreground hover:bg-muted': !scrollIsAtRight,
        'cursor-default opacity-30': scrollIsAtRight,
      }"
      :disabled="scrollIsAtRight"
      aria-label="Scroll tabs right"
      class="border-l px-2"
      type="button"
      @click="scrollDirection('right')"
    >
      <ChevronsRight aria-hidden="true" class="size-4 h-full" />
    </button>
  </div>
</template>
