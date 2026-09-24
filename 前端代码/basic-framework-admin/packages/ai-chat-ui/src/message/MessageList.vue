<script setup lang="ts">
/**
 * 消息列表（C03）：会话消息的**降级渲染入口**。
 *
 * <p>为什么降级发生在这一层：消息块可能来自服务端、历史存档或更早版本的协议（例如平台 API 草案里的
 * `type` 判别键）。渲染器不能因为一块不认识就整条消息留白，也不能"尽量画点什么"——两种做法都会让
 * 用户分不清"没有内容"和"内容没被支持"。所以这里逐块降级：**能渲染的照常渲染，不能渲染的明确写清
 * 来源类型与原因**（`data-testid="ai-message-unsupported"`），一块坏不影响同一条消息的其它块。
 *
 * <p>副作用只往上传（answer / confirm / reject / open-report）：报表打开、动作确认、追问回答都要
 * 重新鉴权并带幂等键，属于宿主编排，消息层不自己发请求。
 */
import type { MessagePorts } from './MessageBlockView.vue';

import { computed } from 'vue';

import { toRenderableBlocks } from './blocks';
import MessageBlockView from './MessageBlockView.vue';

/** 界面消息（块保持 `unknown`：宿主可以原样透传服务端/存档数据，校验在本层完成）。 */
export interface MessageItem {
  blocks: unknown[];
  id: string;
  role: 'assistant' | 'user';
}

const props = withDefaults(
  defineProps<{
    allowedOrigins?: string[];
    messages: MessageItem[];
    ports?: MessagePorts;
  }>(),
  { allowedOrigins: () => [], ports: () => ({}) },
);

const emit = defineEmits<{
  answer: [value: string];
  confirm: [actionId: string];
  openReport: [reportId: string];
  reject: [actionId: string];
}>();

/** 逐块降级解析（不可渲染项带来源类型与原因，见 blocks.ts）。 */
const rendered = computed(() =>
  props.messages.map((message) => ({
    blocks: toRenderableBlocks(message.blocks),
    id: message.id,
    role: message.role,
  })),
);
</script>

<template>
  <ol class="ai-message-list" data-testid="ai-message-list">
    <li
      v-for="message in rendered"
      :key="message.id"
      :data-role="message.role"
      class="ai-message-list__item"
      data-testid="ai-message-item"
    >
      <template v-for="(block, index) in message.blocks" :key="index">
        <MessageBlockView
          v-if="block.kind === 'supported'"
          :allowed-origins="allowedOrigins"
          :block="block.block"
          :ports="ports"
          @answer="emit('answer', $event)"
          @confirm="emit('confirm', $event)"
          @open-report="emit('openReport', $event)"
          @reject="emit('reject', $event)"
        />
        <p
          v-else
          class="ai-message-list__unsupported"
          data-testid="ai-message-unsupported"
        >
          不支持的结果类型 {{ block.sourceKind }}：{{ block.reason }}
        </p>
      </template>
    </li>
  </ol>
  <p v-if="rendered.length === 0" data-testid="ai-message-list-empty">
    暂无消息
  </p>
</template>
