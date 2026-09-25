<script setup lang="ts">
/**
 * Chat 布局外壳（C07）：iframe 内部的三形态骨架（内嵌 / 侧栏 / 弹窗）。
 *
 * <p>为什么 iframe 内也要区分形态：宿主的外壳只解决"面板放在哪里"，而**可访问性语义**必须与形态一致——
 * 弹窗与侧栏是模态区域（`role="dialog"`、Esc 关闭、标题可读），内嵌只是页面里的一块（`role="region"`）。
 * 两者混用会让读屏用户误以为页面只有一块内容，或者以为随时可以 Esc 退出。
 *
 * <p>三条实现约束：
 * <ol>
 *   <li><b>滚动位置不因形态/主题变化而丢</b>：滚动容器始终在 DOM 里（不用 `v-if` 换容器），
 *       换形态只改 class 与语义，消息列表的滚动位置保持；</li>
 *   <li><b>窄屏可用</b>：宽度小于断点时进入窄屏布局（由宿主的外壳令牌给出断点），
 *       标题栏与关闭入口保持可点、字号与行高不缩成"看不清"；</li>
 *   <li><b>键盘可达</b>：关闭是原生 `<button>`，Esc 在模态形态下等价于关闭；内嵌形态不抢 Esc，
 *       避免宿主页面自己的快捷键被吞掉。</li>
 * </ol>
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

const props = withDefaults(
  defineProps<{
    mode?: 'dialog' | 'drawer' | 'inline';
    narrowBreakpoint?: number;
    title?: string;
  }>(),
  { mode: 'inline', narrowBreakpoint: 768, title: 'AI 助手' },
);

const emit = defineEmits<{ close: [] }>();

const root = ref<HTMLElement>();
const isNarrow = ref(false);
let observer: ResizeObserver | undefined;

/** 断点来自主题令牌（宿主外壳与 iframe 内使用同一值，避免两处不一致的"窄屏"）。 */
const narrowBreakpoint = computed(() => Math.max(240, props.narrowBreakpoint));
const isModal = computed(() => props.mode !== 'inline');

function evaluateNarrow(width?: number): void {
  const measured = width ?? root.value?.clientWidth ?? props.narrowBreakpoint;
  isNarrow.value = measured < narrowBreakpoint.value;
}

function onKeydown(event: KeyboardEvent): void {
  if (isModal.value && event.key === 'Escape') {
    event.preventDefault();
    emit('close');
  }
}

onMounted(() => {
  evaluateNarrow();
  if (typeof ResizeObserver === 'function' && root.value) {
    observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width;
      evaluateNarrow(width);
    });
    observer.observe(root.value);
  }
});

onBeforeUnmount(() => {
  // 销毁时释放观察者，避免宿主反复挂载时累积泄漏
  observer?.disconnect();
  observer = undefined;
});

defineExpose({ evaluateNarrow });
</script>

<template>
  <section
    ref="root"
    :aria-label="title"
    :aria-modal="isModal ? 'true' : undefined"
    :data-mode="mode"
    :data-narrow="isNarrow ? 'true' : 'false'"
    :role="isModal ? 'dialog' : 'region'"
    class="ai-chat-layout"
    @keydown="onKeydown"
  >
    <header class="ai-chat-layout__header">
      <h2 class="ai-chat-layout__title">{{ title }}</h2>
      <button
        aria-label="关闭"
        class="ai-chat-layout__close"
        data-testid="ai-chat-layout-close"
        type="button"
        @click="emit('close')"
      >
        关闭
      </button>
    </header>
    <div class="ai-chat-layout__body" data-testid="ai-chat-layout-body">
      <slot></slot>
    </div>
  </section>
</template>
