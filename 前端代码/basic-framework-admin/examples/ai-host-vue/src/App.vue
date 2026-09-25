<script setup lang="ts">
/**
 * Vue 宿主外壳（C10）：内嵌区块 + 主题/上下文/用户切换按钮。
 *
 * 宿主自己提供 UI 与编排；嵌入页（iframe）由平台自托管入口提供，宿主只负责换票与事件校验。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue';

import { createChatMount } from '@vben/ai-embed-sdk';

import {
  applyTheme,
  buildVueHostOptions,
  createVueHostState,
  switchUser,
} from './host';

const appCode = 'crm-portal';
const embedBasePath = 'http://localhost:48080';
const container = ref<HTMLElement>();
const status = ref('未挂载');
const mode = ref<'dialog' | 'drawer' | 'inline'>('inline');
const preset = ref<'dark' | 'light'>('light');
let mount: ReturnType<typeof createChatMount> | undefined;

const hostState = createVueHostState({
  onNavigate: (route, params) => {
    status.value = `导航请求：${route} ${JSON.stringify(params)}（由宿主决定跳转）`;
  },
  onReportCreated: (reportId) => {
    status.value = `报表已创建：${reportId}`;
  },
});

async function fetchTicket(): Promise<{ expiresAt: string; token: string }> {
  const response = await fetch('/your-backend/ai-ticket', { method: 'POST' });
  if (!response.ok) {
    throw new Error(`ticket ${response.status}`);
  }
  return response.json();
}

onMounted(() => {
  if (!container.value) {
    return;
  }
  mount = createChatMount(
    buildVueHostOptions({
      appCode,
      container: container.value,
      embedBasePath,
      fetchTicket,
      instanceId: `vue-host-${Math.random().toString(36).slice(2, 10)}`,
      layout: { minSidebarWidth: 360, narrowBreakpoint: 768 },
    }),
  );
  mount.open();
  status.value = '已挂载（票据由宿主后端换取，页面只持有短期票据）';
});

onBeforeUnmount(() => {
  mount?.destroy();
});
</script>

<template>
  <main class="host">
    <h1>Vue 宿主示例</h1>
    <p>
      宿主与嵌入页不同源；主题与上下文由宿主决定，权限与数据范围始终由服务端判定。
    </p>
    <p data-testid="status">{{ status }}</p>
    <div class="row">
      <button type="button" @click="mount?.setMode('inline')">内嵌</button>
      <button type="button" @click="mount?.setMode('drawer')">侧栏</button>
      <button type="button" @click="mount?.setMode('dialog')">弹窗</button>
      <button
        type="button"
        @click="
          preset = 'dark';
          mount && applyTheme(mount, 'dark');
        "
      >
        深色
      </button>
      <button
        type="button"
        @click="
          preset = 'light';
          mount && applyTheme(mount, 'light');
        "
      >
        浅色
      </button>
      <button
        type="button"
        @click="
          hostState.contexts.update({ objectId: 'order-2', page: 'crm/order' })
        "
      >
        切换上下文
      </button>
      <button
        type="button"
        @click="mount && switchUser(mount, hostState.contexts)"
      >
        切换用户
      </button>
      <button type="button" @click="mount?.close()">关闭</button>
    </div>
    <p class="hint">当前形态：{{ mode }} · 主题：{{ preset }}</p>
    <div ref="container" class="embed"></div>
  </main>
</template>

<style scoped>
.host {
  padding: 16px;
  font-family: system-ui, sans-serif;
}

.row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}

.hint {
  font-size: 12px;
  color: #666;
}

.embed {
  min-height: 320px;
  border: 1px dashed #bbb;
}
</style>
