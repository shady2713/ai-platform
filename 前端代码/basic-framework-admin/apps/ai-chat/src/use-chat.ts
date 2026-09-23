import type { ChatMessage } from '@vben/ai-chat-ui';
import type { AiChatClient } from '@vben/ai-embed-sdk';

import { ref } from 'vue';

/**
 * 独立 Chat 的会话逻辑：只依赖 SDK 契约，不直接访问网络与票据存储。
 *
 * - 幂等键在发送前生成，重复点击/重试复用同一键；
 * - 未配置 API 基址时给出明确错误块，而不是静默失败；
 * - 受理成功后展示 runId/status；SSE 订阅随 O05 接入本 composable。
 */
export function createIdempotencyKey(): string {
  const random =
    globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `chat-${random}`.slice(0, 128).padEnd(16, '0');
}

export function useAiChat(options: {
  client: AiChatClient | null;
  serviceId: string;
}) {
  const messages = ref<ChatMessage[]>([]);
  const pending = ref(false);
  let sequence = 0;

  function push(
    role: ChatMessage['role'],
    blocks: ChatMessage['blocks'],
  ): void {
    sequence += 1;
    messages.value.push({ blocks, id: `m${sequence}`, role });
  }

  async function send(text: string): Promise<void> {
    push('user', [{ kind: 'text', text }]);
    if (!options.client) {
      push('assistant', [
        { kind: 'error', message: '未配置 AI 服务地址，无法发送' },
      ]);
      return;
    }
    pending.value = true;
    try {
      const accepted = await options.client.createRun(
        { message: text, serviceId: options.serviceId },
        createIdempotencyKey(),
      );
      push('assistant', [
        {
          kind: 'text',
          // 对外展示业务键（runKey）；数值编号只用于接口调用
          text: `已受理运行 ${accepted.runKey}（${accepted.status}）`,
        },
      ]);
    } catch (error) {
      push('assistant', [
        {
          kind: 'error',
          message: error instanceof Error ? error.message : '发送失败',
        },
      ]);
    } finally {
      pending.value = false;
    }
  }

  return { messages, pending, send };
}
