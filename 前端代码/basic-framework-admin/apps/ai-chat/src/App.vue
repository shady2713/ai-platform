<script setup lang="ts">
/**
 * 独立 Chat 应用外壳（C02）：会话面板 + 应用端客户端装配。
 *
 * - 未配置基址时面板仍可用（发送给出明确错误块，不静默失败）；
 * - 票据在调用时从宿主存储读取，客户端不持有凭据；
 * - 会话列表/新建/重命名/删除/发送/取消/重试都由 ConversationPanel 与状态机承担。
 */
import { computed } from 'vue';

import { ConversationPanel } from '@vben/ai-chat-ui';

import { buildAiChatClient } from './client-factory';
import { createConversationApi } from './conversation-api';

const baseUrl = import.meta.env.VITE_AI_API_BASE_URL?.trim() ?? '';
const ticketStorage = globalThis.localStorage;

const runApi = buildAiChatClient({ baseUrl, ticketStorage });
const conversationApi = createConversationApi({
  baseUrl: baseUrl || '/app-api',
  ticketStorage,
});

const serviceId = import.meta.env.VITE_AI_SERVICE_ID ?? 'svc_unset';
const statusText = computed(() => (runApi ? '就绪' : '未配置 AI 服务地址'));
</script>

<template>
  <main class="ai-chat-app">
    <header class="ai-chat-app__header">
      <h1>AI 助手</h1>
      <span class="ai-chat-app__status" data-testid="ai-chat-status">{{
        statusText
      }}</span>
    </header>
    <ConversationPanel
      :api="conversationApi"
      :run-api="runApi"
      :service-id="serviceId"
    />
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
