<script setup lang="ts">
import type { ChartSpec, ResultBlock } from '@vben/ai-contracts';

import { ref } from 'vue';

import ChartRenderer from './ChartRenderer.vue';

export interface ChatMessage {
  blocks: ResultBlock[];
  id: string;
  role: 'assistant' | 'user';
}

/**
 * AiChatPanel：独立 Chat 的最小可运行界面。
 *
 * 只负责消息列表与输入；运行受理、SSE 与票据由 SDK/宿主负责，
 * 面板不直接访问网络，便于在管理端与独立 Chat 中复用。
 */
const props = withDefaults(
  defineProps<{ disabled?: boolean; messages: ChatMessage[] }>(),
  { disabled: false },
);

const emit = defineEmits<{ send: [text: string] }>();

const draft = ref('');

function chartSpecOf(block: ResultBlock): ChartSpec | null {
  return block.kind === 'chart' ? block.spec : null;
}

function messageText(block: ResultBlock): string {
  return block.kind === 'text' ? block.text : '';
}

function messageError(block: ResultBlock): string {
  return block.kind === 'error' ? block.message : '';
}

function canSubmit(): boolean {
  return !props.disabled && draft.value.trim().length > 0;
}

function submit(): void {
  if (!canSubmit()) {
    return;
  }
  emit('send', draft.value.trim());
  draft.value = '';
}
</script>

<template>
  <section class="ai-chat-panel">
    <ol class="ai-chat-panel__messages" data-testid="ai-chat-messages">
      <li
        v-for="message in messages"
        :key="message.id"
        :data-role="message.role"
        class="ai-chat-panel__message"
      >
        <template
          v-for="(block, index) in message.blocks"
          :key="`${message.id}-${index}`"
        >
          <p v-if="block.kind === 'text'" class="ai-chat-panel__text">
            {{ messageText(block) }}
          </p>
          <ChartRenderer
            v-else-if="chartSpecOf(block)"
            :spec="chartSpecOf(block) as ChartSpec"
          />
          <p v-else class="ai-chat-panel__error" role="alert">
            {{ messageError(block) }}
          </p>
        </template>
      </li>
    </ol>
    <form class="ai-chat-panel__composer" @submit.prevent="submit">
      <input
        v-model="draft"
        :disabled="disabled"
        aria-label="输入消息"
        class="ai-chat-panel__input"
        placeholder="输入消息"
        type="text"
      />
      <button
        class="ai-chat-panel__submit"
        :disabled="!canSubmit()"
        data-testid="ai-chat-send"
        type="submit"
      >
        发送
      </button>
    </form>
  </section>
</template>
