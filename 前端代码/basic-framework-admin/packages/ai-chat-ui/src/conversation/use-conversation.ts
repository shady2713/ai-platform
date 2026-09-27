import type { ResultBlock } from '@vben/ai-contracts';

import { computed, ref } from 'vue';

import { createConversationMachine } from './state';

/**
 * 会话逻辑（C02）：列表 CRUD + 发送/取消/重试 + 状态机 + 代次隔离。
 *
 * <p>为什么把"会话接口"做成注入的端口：本包不依赖具体请求库（宿主可能是独立 Chat 应用、后台管理端或
 * 第三方宿主），端口由宿主实现；本 composable 只负责**状态与并发语义**，因此可用假端口做完整单测。
 *
 * <p>四条硬语义（与验收对应）：
 * <ol>
 *   <li><b>连续点击不重复受理</b>：执行中/确认中调用 `send` 直接忽略（同一运行只受理一次）；</li>
 *   <li><b>取消后晚到的完成不覆盖终态</b>：取消即进入失败态，之后到达的事件按 seq/阶段规则丢弃；</li>
 *   <li><b>切用户/切会话丢弃旧响应</b>：代次 +1 后，旧代次的回调（事件、受理结果、错误）全部丢弃；</li>
 *   <li><b>错误只提示一次</b>：同一错误键只追加一次错误块（重复失败不刷屏）。</li>
 * </ol>
 */

/** 会话摘要（与 O01 应用端契约一致）。 */
export interface ConversationSummary {
  conversationKey: string;
  id: number;
  title: string;
  updateTime?: string;
}

/** 会话接口端口（宿主实现：独立 Chat 应用 / 管理端 / 第三方宿主）。 */
export interface ConversationApi {
  create(title?: string): Promise<ConversationSummary>;
  list(): Promise<ConversationSummary[]>;
  remove(id: number): Promise<void>;
  rename(id: number, title: string): Promise<void>;
}

/** 运行端口（C01 共享客户端的子集）。 */
export interface ConversationRunApi {
  cancelRun(runKey: string, version?: number): Promise<unknown>;
  createRun(
    request: { conversationId?: number; message: string; serviceId: string },
    idempotencyKey: string,
  ): Promise<{ runId: number; runKey: string; status: string }>;
  streamRunEvents?: (
    runKey: string,
    handlers: {
      afterSeq?: number;
      /** 事件可携带受控结果块（冻结 v1：文本/图表），界面不得丢弃图表 */
      onEvent: (event: {
        block?: ResultBlock;
        seq: number;
        status: string;
      }) => void;
    },
  ) => Promise<{ lastSeq: number; reason: string }>;
}

/** 界面消息（与 @vben/ai-chat-ui 的 ChatMessage 同形）。 */
export interface ConversationMessage {
  blocks: ResultBlock[];
  id: string;
  role: 'assistant' | 'user';
}

export function createIdempotencyKey(): string {
  const random =
    globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `chat-${random}`.slice(0, 128).padEnd(16, '0');
}

/** 列表面的失败快照：稳定原因码 + 可读消息（消息只按文本渲染，不回显 HTML）。 */
export interface ConversationLoadFailure {
  code: string;
  message: string;
}

/** 从任意异常归一化失败快照：优先稳定原因码，其次由状态码派生，最后兜底。 */
export function toLoadFailure(error: unknown): ConversationLoadFailure {
  const record =
    typeof error === 'object' && error !== null
      ? (error as Record<string, unknown>)
      : {};
  const code =
    typeof record.code === 'string' && record.code.length > 0
      ? record.code
      : null;
  const status =
    typeof record.status === 'number' && record.status > 0
      ? record.status
      : null;
  const message =
    error instanceof Error && error.message.trim() !== ''
      ? error.message
      : null;
  return {
    code: code ?? (status === null ? 'REQUEST_FAILED' : `HTTP_${status}`),
    message: message ?? '会话列表加载失败，请稍后重试',
  };
}

export interface UseConversationOptions {
  api: ConversationApi;
  runApi: ConversationRunApi | null;
  serviceId: string;
}

export function useConversation(options: UseConversationOptions) {
  const machine = createConversationMachine();
  const conversations = ref<ConversationSummary[]>([]);
  const active = ref<ConversationSummary>();
  const messages = ref<ConversationMessage[]>([]);
  const loadFailure = ref<ConversationLoadFailure>();
  const phase = ref(machine.snapshot().phase);
  let sequence = 0;

  function syncPhase(): void {
    phase.value = machine.snapshot().phase;
  }

  function push(
    role: ConversationMessage['role'],
    blocks: ResultBlock[],
  ): void {
    sequence += 1;
    messages.value.push({ blocks, id: `m${sequence}`, role });
  }

  /**
   * 运行事件的结果块追加到当前运行的助手消息。
   *
   * <p>受理成功时 `send` 已先推入本轮助手消息，所以这里直接追加到最后一条；
   * 若宿主在运行中换会话/换用户，事件在代次过滤处已丢弃（消息列表不会被"半清空"）。
   */
  function appendRunBlock(block: ResultBlock): void {
    messages.value.at(-1)?.blocks.push(block);
  }

  async function refreshList(): Promise<void> {
    try {
      conversations.value = await options.api.list();
      loadFailure.value = undefined;
    } catch (error) {
      // 列表加载失败：记录稳定原因由界面展示，不向宿主抛出未处理的 rejection
      // （挂载即加载由组件发起，宿主没有可用的捕获点）。
      loadFailure.value = toLoadFailure(error);
    }
  }

  /**
   * 列表面的操作（新建/重命名/删除）统一在此捕获失败：
   * 失败原因进 `loadFailure` 并由界面展示；操作本身不向宿主抛未处理的 rejection。
   */
  async function performListAction(action: () => Promise<void>): Promise<void> {
    try {
      await action();
      loadFailure.value = undefined;
    } catch (error) {
      loadFailure.value = toLoadFailure(error);
    }
  }

  function createConversation(title?: string): Promise<void> {
    return performListAction(async () => {
      const created = await options.api.create(title);
      await refreshList();
      await selectConversation(created.id);
    });
  }

  function renameConversation(id: number, title: string): Promise<void> {
    return performListAction(async () => {
      await options.api.rename(id, title);
      await refreshList();
    });
  }

  function deleteConversation(id: number): Promise<void> {
    return performListAction(async () => {
      await options.api.remove(id);
      if (active.value?.id === id) {
        // 删除当前会话：清空界面并换代（旧响应不再进入界面）
        machine.switchGeneration();
        active.value = undefined;
        messages.value = [];
        syncPhase();
      }
      await refreshList();
    });
  }

  /** 选择会话：换代 + 清空消息（AT-053 的会话内版本）。 */
  async function selectConversation(id: number): Promise<void> {
    machine.switchGeneration();
    active.value = conversations.value.find((item) => item.id === id);
    messages.value = [];
    syncPhase();
  }

  /** 宿主切换用户（或销毁实例）：换代并清空，晚到的旧响应被丢弃。 */
  function switchUser(): void {
    machine.switchGeneration();
    active.value = undefined;
    messages.value = [];
    syncPhase();
  }

  async function send(text: string): Promise<void> {
    const generation = machine.snapshot().generation;
    const idempotencyKey = createIdempotencyKey();
    if (!machine.beginSend(generation, idempotencyKey)) {
      // 执行中/确认中：连续点击不重复受理
      return;
    }
    push('user', [{ kind: 'text', text }]);
    if (!options.runApi) {
      push('assistant', [
        { kind: 'error', message: '未配置 AI 服务地址，无法发送' },
      ]);
      machine.fail('NO_CLIENT', generation);
      syncPhase();
      return;
    }
    try {
      const accepted = await options.runApi.createRun(
        {
          ...(active.value ? { conversationId: active.value.id } : {}),
          message: text,
          serviceId: options.serviceId,
        },
        idempotencyKey,
      );
      if (!machine.acceptRun(accepted.runKey, generation)) {
        // 切用户/切会话后晚到的受理结果：丢弃
        return;
      }
      syncPhase();
      push('assistant', [
        {
          kind: 'text',
          text: `已受理运行 ${accepted.runKey}（${accepted.status}）`,
        },
      ]);
      if (options.runApi.streamRunEvents) {
        await options.runApi.streamRunEvents(accepted.runKey, {
          onEvent: (event) => {
            // 代次与 seq 过滤（切用户/切会话后的旧事件在此丢弃，结果块也不会落到新会话）
            if (!machine.applyEvent(event, generation)) {
              return;
            }
            if (event.block) {
              appendRunBlock(event.block);
            }
            syncPhase();
          },
        });
      }
    } catch (error) {
      const key = error instanceof Error ? error.message : '未知错误';
      const shouldNotify = machine.fail(key, generation);
      // 阶段始终以状态机为准；错误块只在"新错误"时追加一次（错误只提示一次）
      syncPhase();
      if (shouldNotify) {
        push('assistant', [{ kind: 'error', message: key }]);
      }
    }
  }

  async function cancel(): Promise<void> {
    const generation = machine.snapshot().generation;
    const runKey = machine.snapshot().runKey;
    if (!machine.cancel(generation)) {
      return;
    }
    syncPhase();
    if (runKey && options.runApi) {
      // 取消是显式动作：失败也不回滚本地终态（服务端以事件为准）
      await options.runApi.cancelRun(runKey).catch(() => undefined);
    }
  }

  async function retry(): Promise<void> {
    const generation = machine.snapshot().generation;
    const lastUserText = messages.value
      .toReversed()
      .find((message) => message.role === 'user')
      ?.blocks.find((block) => block.kind === 'text');
    const { accepted } = machine.retry(generation);
    if (!accepted) {
      return;
    }
    syncPhase();
    if (lastUserText && lastUserText.kind === 'text') {
      await send(lastUserText.text);
    }
  }

  return {
    active,
    cancel,
    conversations: computed(() => conversations.value),
    createConversation,
    deleteConversation,
    loadFailure: computed(() => loadFailure.value),
    messages: computed(() => messages.value),
    phase: computed(() => phase.value),
    refreshList,
    renameConversation,
    retry,
    selectConversation,
    send,
    switchUser,
  };
}
