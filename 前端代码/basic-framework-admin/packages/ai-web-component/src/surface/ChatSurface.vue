<script setup lang="ts">
/**
 * 组件内的 Chat 界面（X09）：直接复用现有 ChatUI 的会话面板。
 *
 * <p>为什么只做一层薄封装：会话状态机、列表 CRUD、发送/取消/重试的并发语义都在
 * `@vben/ai-chat-ui` 的 `ConversationPanel` + `useConversation` 里（不复制业务状态机）；
 * 本组件只负责把它挂进 Shadow DOM、把宿主提供的端口与主题令牌接进来。
 */
import type { ConversationApi, ConversationRunApi } from '@vben/ai-chat-ui';

import { ConversationPanel } from '@vben/ai-chat-ui';

defineProps<{
  api: ConversationApi;
  runApi: ConversationRunApi | null;
  serviceId: string;
}>();
</script>

<template>
  <ConversationPanel :api="api" :run-api="runApi" :service-id="serviceId" />
</template>
