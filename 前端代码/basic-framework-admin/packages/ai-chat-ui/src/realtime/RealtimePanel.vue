<script setup lang="ts">
/**
 * RealtimePanel（X05）：实时语音会话的受控界面。
 *
 * 边界与安全语义：
 * - 只渲染**服务端事实**：转写、工具状态、背压占用与关闭原因都来自会话视图；界面不推断可用性，
 *   受理被拒绝时按稳定码进入失败/失权态，不自动改用其它协议或端点重试；
 * - 全部内容文本插值（无 `v-html`/`innerHTML`）：模型/上游文本只能是文本；
 * - 打断先本地推进回合（由状态机负责）：旧回合的晚到响应不会继续进入界面；
 * - 卸载或切换用户时销毁状态机（身份不可跨会话复用），晚到响应一律丢弃。
 */
import type {
  RealtimeAcceptInput,
  RealtimeMachine,
  RealtimeSnapshot,
} from './state';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { realtimeErrorMessage } from './state';

const props = withDefaults(
  defineProps<{
    /** 受理输入（端点/协议/音频格式由宿主显式给出） */
    acceptInput: RealtimeAcceptInput;
    /** 状态机（宿主用 createRealtimeMachine 创建） */
    machine: RealtimeMachine;
  }>(),
  {},
);

const snapshot = ref<RealtimeSnapshot>(props.machine.snapshot());

let unsubscribe: (() => void) | null = null;

onMounted(() => {
  unsubscribe = props.machine.subscribe((next) => {
    snapshot.value = next;
  });
});

onBeforeUnmount(() => {
  unsubscribe?.();
  unsubscribe = null;
  // 销毁：切换用户/离开页面后，任何晚到的响应都不再写状态
  props.machine.destroy();
});

const phase = computed(() => snapshot.value.phase);
const session = computed(() => snapshot.value.session);
const transcript = computed(() => snapshot.value.transcript);
const toolCalls = computed(() => session.value?.toolCalls ?? []);
const pressure = computed(() => snapshot.value.pressure);
const errorText = computed(() =>
  snapshot.value.errorKey ? realtimeErrorMessage(errorOf(snapshot.value)) : '',
);
const droppedFrames = computed(
  () =>
    (session.value?.droppedStaleFrames ?? 0) +
    snapshot.value.localDroppedFrames,
);
const statusText = computed(() => {
  switch (session.value?.status) {
    case 'CLOSED': {
      return '已结束';
    }
    case 'DETACHED': {
      return '已断开（可重连）';
    }
    case 'OPEN': {
      return '会话中';
    }
    default: {
      return '未开始';
    }
  }
});

/** 稳定错误键 → 错误形状（提示文本由 realtimeErrorMessage 统一给出）。 */
function errorOf(current: RealtimeSnapshot): unknown {
  if (!current.errorKey) {
    return null;
  }
  const code = Number(current.errorKey.replace('realtime-error-', ''));
  return Number.isFinite(code) ? { code } : null;
}

async function start(): Promise<void> {
  await props.machine.accept(props.acceptInput);
}

async function interrupt(): Promise<void> {
  await props.machine.interrupt();
}

async function toggleMute(): Promise<void> {
  await props.machine.mute(!(session.value?.muted ?? false));
}

async function resume(): Promise<void> {
  const ticket = session.value?.ticket;
  if (ticket) {
    await props.machine.resume(ticket);
  }
}

async function close(): Promise<void> {
  await props.machine.close();
}
</script>

<template>
  <section class="realtime-panel" data-testid="realtime-panel">
    <header class="realtime-panel__header">
      <span class="realtime-panel__title">实时语音</span>
      <span class="realtime-panel__status" data-testid="realtime-status">{{
        statusText
      }}</span>
      <span
        v-if="pressure === 'HIGH'"
        class="realtime-panel__pressure"
        data-testid="realtime-pressure"
      >
        输入缓冲接近上限：继续推流会按平台规则结束会话
      </span>
      <span
        v-if="droppedFrames > 0"
        class="realtime-panel__dropped"
        data-testid="realtime-dropped"
      >
        已丢弃 {{ droppedFrames }} 个过期回合帧
      </span>
    </header>

    <p
      v-if="phase === 'LOADING'"
      class="realtime-panel__hint"
      data-testid="realtime-loading"
    >
      正在建立实时会话…
    </p>

    <p
      v-else-if="phase === 'EMPTY'"
      class="realtime-panel__hint"
      data-testid="realtime-empty"
    >
      <template v-if="session?.closeReason">
        会话已结束（{{ session.closeReason }}），可重新开始。
      </template>
      <template v-else>还没有进行中的实时会话。</template>
    </p>

    <p
      v-else-if="phase === 'FAILED'"
      class="realtime-panel__error"
      data-testid="realtime-failed"
    >
      {{ errorText }}
    </p>

    <p
      v-else-if="phase === 'DENIED'"
      class="realtime-panel__error"
      data-testid="realtime-denied"
    >
      没有使用实时语音的权限：{{ errorText }}
    </p>

    <p
      v-else-if="phase === 'DESTROYED'"
      class="realtime-panel__hint"
      data-testid="realtime-destroyed"
    >
      会话已销毁（切换用户或离开页面），如需继续请重新开始。
    </p>

    <template v-else>
      <dl class="realtime-panel__meta" data-testid="realtime-meta">
        <div>
          <dt>协议</dt>
          <dd>{{ session?.protocol }}</dd>
        </div>
        <div>
          <dt>音频格式</dt>
          <dd>{{ session?.audioFormat }}</dd>
        </div>
        <div>
          <dt>回合</dt>
          <dd data-testid="realtime-turn">{{ snapshot.turnNo }}</dd>
        </div>
        <div>
          <dt>到期时间</dt>
          <dd>{{ session?.expiresTime }}</dd>
        </div>
      </dl>

      <ol class="realtime-panel__transcript" data-testid="realtime-transcript">
        <li
          v-for="line in transcript"
          :key="`${line.turnNo}-${line.seq}-${line.text}`"
          :class="{ 'realtime-panel__partial': !line.final }"
        >
          <span class="realtime-panel__turn">回合 {{ line.turnNo }}</span>
          <span>{{ line.text }}</span>
          <span v-if="!line.final" class="realtime-panel__tag">识别中</span>
        </li>
      </ol>

      <ul
        v-if="toolCalls.length > 0"
        class="realtime-panel__tools"
        data-testid="realtime-tools"
      >
        <li v-for="call in toolCalls" :key="call.id">
          <span>{{ call.toolCode }}</span>
          <span class="realtime-panel__tag">{{ call.status }}</span>
          <button
            v-if="call.status === 'PROPOSED'"
            type="button"
            data-testid="realtime-tool-execute"
            @click="machine.executeToolCall(call.id)"
          >
            执行
          </button>
        </li>
      </ul>
    </template>

    <footer class="realtime-panel__actions">
      <button
        v-if="phase === 'EMPTY' || phase === 'FAILED' || phase === 'DENIED'"
        type="button"
        data-testid="realtime-start"
        @click="start"
      >
        开始会话
      </button>
      <template v-if="phase === 'ACTIVE'">
        <button
          type="button"
          data-testid="realtime-interrupt"
          @click="interrupt"
        >
          打断
        </button>
        <button type="button" data-testid="realtime-mute" @click="toggleMute">
          {{ session?.muted ? '开麦' : '关麦' }}
        </button>
        <button
          v-if="session?.status === 'DETACHED'"
          type="button"
          data-testid="realtime-resume"
          @click="resume"
        >
          重连
        </button>
        <button type="button" data-testid="realtime-close" @click="close">
          结束会话
        </button>
      </template>
    </footer>
  </section>
</template>

<style scoped>
.realtime-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.realtime-panel__header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
}

.realtime-panel__title {
  font-weight: 600;
}

.realtime-panel__pressure,
.realtime-panel__dropped {
  font-size: 12px;
  color: #b45309;
}

.realtime-panel__error {
  color: #b91c1c;
}

.realtime-panel__hint {
  color: #4b5563;
}

.realtime-panel__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 0;
  font-size: 12px;
}

.realtime-panel__meta dt {
  color: #6b7280;
}

.realtime-panel__meta dd {
  margin: 0;
}

.realtime-panel__transcript {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  padding-left: 18px;
  margin: 0;
  overflow-y: auto;
}

.realtime-panel__partial {
  color: #6b7280;
}

.realtime-panel__turn,
.realtime-panel__tag {
  margin-right: 6px;
  font-size: 12px;
  color: #6b7280;
}

.realtime-panel__tools {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-left: 18px;
  margin: 0;
}

.realtime-panel__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
