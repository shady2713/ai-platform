<script setup lang="ts">
/**
 * 待确认动作（C03）：按钮只携带 `actionId`，**不携带任何请求体**。
 *
 * <p>设计契约要求"待确认操作，按钮不直接携带任意请求"（`04-api-chat-integration.md` §5）：
 * 参数只以**摘要文本**展示给用户看，真正的执行由服务端按 `actionId` 取回并重新鉴权；
 * 过期或被取消/已确认的动作不再给按钮（不给"点了必然失败"的入口）。
 */
import type { ActionBlock } from './blocks';

import { computed } from 'vue';

import { isActionExpired } from './blocks';

const props = defineProps<{ block: ActionBlock; now?: number }>();

const emit = defineEmits<{
  confirm: [actionId: string];
  reject: [actionId: string];
}>();

/** 已处理（确认/取消）优先于过期：两者提示语义不同，不能都说成"过期"。 */
const settled = computed(
  () =>
    props.block.status === 'CANCELLED' || props.block.status === 'CONFIRMED',
);

const expired = computed(
  () => !settled.value && isActionExpired(props.block, props.now ?? Date.now()),
);

const actionable = computed(() => !settled.value && !expired.value);

function confirm(): void {
  if (actionable.value) {
    emit('confirm', props.block.actionId);
  }
}

function reject(): void {
  if (actionable.value) {
    emit('reject', props.block.actionId);
  }
}
</script>

<template>
  <section class="ai-action" data-testid="ai-message-action">
    <p class="ai-action__tool" data-testid="ai-action-tool">
      {{ block.toolName }}
    </p>
    <p class="ai-action__summary" data-testid="ai-action-summary">
      {{ block.parameterSummary }}
    </p>
    <p class="ai-action__expiry" data-testid="ai-action-expiry">
      有效期至 {{ block.expiresAt }}
    </p>
    <p v-if="settled" class="ai-action__state" data-testid="ai-action-settled">
      该操作已处理完成
    </p>
    <p
      v-else-if="expired"
      class="ai-action__state"
      data-testid="ai-action-expired"
    >
      该操作已过期，请重新发起
    </p>
    <span v-else class="ai-action__controls">
      <button data-testid="ai-action-confirm" type="button" @click="confirm">
        确认执行
      </button>
      <button data-testid="ai-action-reject" type="button" @click="reject">
        取消
      </button>
    </span>
  </section>
</template>
