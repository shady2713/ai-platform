<script setup lang="ts">
/**
 * 会话面板（C02）：会话列表（新建/重命名/删除）+ 消息列表 + 发送/取消/重试 + 阶段徽标。
 *
 * <p>组件只做展示与转发：状态与并发语义都在 `useConversation` 的状态机里
 * （连续点击不重复受理、取消后晚到完成不覆盖、切用户丢弃旧响应、错误只提示一次）。
 */
import type {
  ConversationApi,
  ConversationRunApi,
  ConversationSummary,
} from './use-conversation';

import { computed, onMounted, ref } from 'vue';

import { useConversation } from './use-conversation';

const props = defineProps<{
  api: ConversationApi;
  runApi: ConversationRunApi | null;
  serviceId: string;
}>();

const chat = useConversation({
  api: props.api,
  runApi: props.runApi,
  serviceId: props.serviceId,
});

const draft = ref('');

// 挂载即加载会话列表（宿主无需额外调用）
onMounted(() => {
  void chat.refreshList();
});
const renameTarget = ref<ConversationSummary>();
const renameTitle = ref('');

/** 阶段文案：等待/执行/确认/失败/完成 */
const phaseText = computed(() => {
  switch (chat.phase.value) {
    case 'FAILED': {
      return '失败（可重试）';
    }
    case 'RUNNING': {
      return '执行中';
    }
    case 'SUCCEEDED': {
      return '已完成';
    }
    case 'WAITING_CONFIRMATION': {
      return '等待确认';
    }
    default: {
      return '等待输入';
    }
  }
});

const canCancel = computed(
  () =>
    chat.phase.value === 'RUNNING' ||
    chat.phase.value === 'WAITING_CONFIRMATION',
);
const canRetry = computed(() => chat.phase.value === 'FAILED');

async function submit(): Promise<void> {
  const text = draft.value.trim();
  if (!text) {
    return;
  }
  draft.value = '';
  await chat.send(text);
}

async function startRename(conversation: ConversationSummary): Promise<void> {
  renameTarget.value = conversation;
  renameTitle.value = conversation.title;
}

async function confirmRename(): Promise<void> {
  if (!renameTarget.value || !renameTitle.value.trim()) {
    return;
  }
  await chat.renameConversation(
    renameTarget.value.id,
    renameTitle.value.trim(),
  );
  renameTarget.value = undefined;
}
</script>

<template>
  <section class="ai-conversation" data-testid="ai-conversation">
    <aside class="ai-conversation__list" data-testid="ai-conversation-list">
      <header class="ai-conversation__list-header">
        <span>会话</span>
        <button
          data-testid="ai-conversation-create"
          type="button"
          @click="chat.createConversation()"
        >
          新建
        </button>
      </header>
      <ul>
        <li
          v-for="conversation in chat.conversations.value"
          :key="conversation.id"
          :class="{ 'is-active': chat.active.value?.id === conversation.id }"
          :data-conversation-id="conversation.id"
          data-testid="ai-conversation-item"
        >
          <button
            type="button"
            @click="chat.selectConversation(conversation.id)"
          >
            {{ conversation.title }}
          </button>
          <span class="ai-conversation__item-actions">
            <button
              data-testid="ai-conversation-rename"
              type="button"
              @click="startRename(conversation)"
            >
              重命名
            </button>
            <button
              data-testid="ai-conversation-delete"
              type="button"
              @click="chat.deleteConversation(conversation.id)"
            >
              删除
            </button>
          </span>
        </li>
      </ul>
      <form
        v-if="renameTarget"
        class="ai-conversation__rename"
        data-testid="ai-conversation-rename-form"
        @submit.prevent="confirmRename"
      >
        <input
          v-model="renameTitle"
          data-testid="ai-conversation-rename-input"
          type="text"
        />
        <button data-testid="ai-conversation-rename-confirm" type="submit">
          保存
        </button>
      </form>
    </aside>

    <div class="ai-conversation__main">
      <header class="ai-conversation__status">
        <span data-testid="ai-conversation-phase">{{ phaseText }}</span>
        <button
          v-if="canCancel"
          data-testid="ai-conversation-cancel"
          type="button"
          @click="chat.cancel()"
        >
          取消
        </button>
        <button
          v-if="canRetry"
          data-testid="ai-conversation-retry"
          type="button"
          @click="chat.retry()"
        >
          重试
        </button>
      </header>

      <ol
        class="ai-conversation__messages"
        data-testid="ai-conversation-messages"
      >
        <li
          v-for="message in chat.messages.value"
          :key="message.id"
          :data-role="message.role"
          data-testid="ai-conversation-message"
        >
          <template v-for="(block, index) in message.blocks" :key="index">
            <p v-if="block.kind === 'text'" data-testid="ai-conversation-text">
              {{ block.text }}
            </p>
            <p
              v-else-if="block.kind === 'error'"
              class="is-error"
              data-testid="ai-conversation-error"
            >
              {{ block.message }}
            </p>
            <p v-else data-testid="ai-conversation-block">
              （{{ block.kind }} 结果块）
            </p>
          </template>
        </li>
      </ol>

      <form class="ai-conversation__compose" @submit.prevent="submit">
        <input
          v-model="draft"
          data-testid="ai-conversation-input"
          placeholder="输入问题后回车发送"
          type="text"
        />
        <button data-testid="ai-conversation-send" type="submit">发送</button>
      </form>
    </div>
  </section>
</template>

<style scoped>
.ai-conversation {
  display: flex;
  gap: 12px;
  min-width: 0;
}

.ai-conversation__list {
  flex: 0 0 200px;
  min-width: 0;
  overflow-x: auto;
}

.ai-conversation__list-header,
.ai-conversation__status,
.ai-conversation__item-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.ai-conversation__list ul {
  padding: 0;
  margin: 8px 0 0;
  list-style: none;
}

.ai-conversation__list li {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  overflow-wrap: anywhere;
}

.ai-conversation__list li.is-active {
  font-weight: 600;
}

.ai-conversation__main {
  flex: 1 1 auto;
  min-width: 0;
}

.ai-conversation__messages {
  padding: 0;
  margin: 8px 0;
  overflow-wrap: anywhere;
  list-style: none;
}

.ai-conversation__messages p {
  margin: 4px 0;
}

.ai-conversation__messages .is-error {
  color: #dc2626;
}

.ai-conversation__compose {
  display: flex;
  gap: 8px;
}

.ai-conversation__compose input {
  flex: 1 1 auto;
  min-width: 0;
}
</style>
