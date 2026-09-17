<script setup lang="ts">
import { computed } from 'vue';

import { AiChatPanel } from '@vben/ai-chat-ui';

import { buildAiChatClient } from './client-factory';
import { useAiChat } from './use-chat';

const client = buildAiChatClient({
  baseUrl: import.meta.env.VITE_AI_API_BASE_URL,
  ticketStorage: globalThis.localStorage,
});

const { messages, pending, send } = useAiChat({
  client,
  serviceId: import.meta.env.VITE_AI_SERVICE_ID ?? 'svc_unset',
});

const statusText = computed(() => (pending.value ? '正在受理…' : '就绪'));
</script>

<template>
  <main class="ai-chat-app">
    <header class="ai-chat-app__header">
      <h1>AI 助手</h1>
      <span class="ai-chat-app__status" data-testid="ai-chat-status">{{
        statusText
      }}</span>
    </header>
    <AiChatPanel :disabled="pending" :messages="messages" @send="send" />
  </main>
</template>

<style scoped>
.ai-chat-app {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 720px;
  padding: 16px;
  margin: 0 auto;
  font-family: inherit;
}

.ai-chat-app__header {
  display: flex;
  gap: 8px;
  align-items: baseline;
  justify-content: space-between;
}

.ai-chat-app__status {
  font-size: 12px;
  color: #666;
}
</style>
