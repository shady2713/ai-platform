<script setup lang="ts">
/**
 * Vue 宿主外壳（C10 + X09）：同一页演示**两条集成路径**，宿主逻辑只写一份。
 *
 * - iframe 路径：`createChatMount` 建 iframe，嵌入页由平台自托管（跨源）；
 * - 组件路径：按固定命名加载**版本化组件产物**并注册 `<ai-chat-component>`，
 *   ChatUI 直接挂进 Shadow DOM（宿主 CSS 进不去，见下方故意写宽的通配规则）。
 *
 * 两条路径共用：登记路由表、换票回调、主题 tokens、切用户与销毁动作（`host.ts` / `component-host.ts`）。
 */
import type { AiChatElement } from '@vben/ai-web-component';

import { onBeforeUnmount, onMounted, ref } from 'vue';

import { createChatMount } from '@vben/ai-embed-sdk';

import {
  buildComponentAttributes,
  createComponentTicketProvider,
  loadComponentArtifact,
  switchComponentUser,
} from './component-host';
import {
  applyTheme,
  buildVueHostOptions,
  createVueHostState,
  switchUser,
} from './host';

const appCode = 'crm-portal';
const embedBasePath = 'http://localhost:48080';
/** 示例占位：真实宿主的服务标识来自应用发布配置，不从页面参数猜 */
const serviceId = 'svc_1';
const componentContainer = ref<HTMLElement>();
const container = ref<HTMLElement>();
const componentStatus = ref('未挂载');
const status = ref('未挂载');
const mode = ref<'dialog' | 'drawer' | 'inline'>('inline');
const preset = ref<'dark' | 'light'>('light');
let mount: ReturnType<typeof createChatMount> | undefined;
let component: AiChatElement | undefined;

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

async function mountComponentPath(): Promise<void> {
  const target = componentContainer.value;
  if (!target) {
    return;
  }
  const artifact = await loadComponentArtifact();
  artifact.defineAiChatElement();
  const element = document.createElement('ai-chat-component') as AiChatElement;
  const attributes = buildComponentAttributes({
    appCode,
    instanceId: `vue-component-${Math.random().toString(36).slice(2, 10)}`,
    serviceId,
  });
  for (const [name, value] of Object.entries(attributes)) {
    element.setAttribute(name, value);
  }
  element.getAccessToken = createComponentTicketProvider(fetch);

  element.addEventListener('ai-ready', () => {
    componentStatus.value = '组件已连接（ChatUI 在 Shadow DOM 内）';
  });
  element.addEventListener('ai-error', (event) => {
    componentStatus.value = `组件错误：${(event as CustomEvent).detail.errorCode}`;
  });
  element.addEventListener('ai-navigate-request', (event) => {
    componentStatus.value = `导航请求：${(event as CustomEvent).detail.route}（由宿主决定跳转）`;
  });
  element.addEventListener('ai-rejected', (event) => {
    componentStatus.value = `请求被拒绝：${(event as CustomEvent).detail.reason}`;
  });

  target.append(element);
  element.open();
  component = element;
  componentStatus.value = '已挂载组件（票据由宿主后端换取）';
}

onMounted(async () => {
  if (container.value) {
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
  }
  try {
    await mountComponentPath();
  } catch (error) {
    // 产物缺失/版本不符：明确报错（不静默降级成 iframe 路径）
    componentStatus.value =
      error instanceof Error ? error.message : '组件产物加载失败';
  }
});

onBeforeUnmount(() => {
  mount?.destroy();
  component?.destroy();
});
</script>

<template>
  <main class="host">
    <h1>Vue 宿主示例</h1>
    <p>
      宿主与嵌入页不同源；主题与上下文由宿主决定，权限与数据范围始终由服务端判定。
      两条路径（iframe /
      组件）在同一页演示，宿主侧的登记路由、换票与主题只有一份实现。
    </p>

    <h2>路径一：iframe 嵌入（跨源）</h2>
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

    <h2>路径二：组件嵌入（Shadow DOM）</h2>
    <p data-testid="component-status">{{ componentStatus }}</p>
    <div class="row">
      <button type="button" @click="component?.setMode('inline')">内嵌</button>
      <button type="button" @click="component?.setMode('drawer')">侧栏</button>
      <button type="button" @click="component?.setMode('dialog')">弹窗</button>
      <button
        type="button"
        @click="
          component?.updateTheme({
            fontFamily:
              'system-ui, -apple-system, \'PingFang SC\', \'Microsoft YaHei\', sans-serif',
            primaryColor: '#7c3aed',
            radius: 8,
          })
        "
      >
        深色令牌
      </button>
      <button
        type="button"
        @click="
          component?.updateTheme({
            fontFamily:
              'system-ui, -apple-system, \'PingFang SC\', \'Microsoft YaHei\', sans-serif',
            primaryColor: '#1677ff',
            radius: 6,
          })
        "
      >
        浅色令牌
      </button>
      <button
        type="button"
        @click="
          component?.updateContext({ objectId: 'order-2', page: 'crm/order' })
        "
      >
        更新上下文
      </button>
      <button
        type="button"
        @click="component?.requestNavigate('order.detail', { id: 'order-1' })"
      >
        请求导航
      </button>
      <button
        type="button"
        @click="component && switchComponentUser(component)"
      >
        切换用户
      </button>
      <button type="button" @click="component?.close()">关闭</button>
    </div>
    <div ref="componentContainer" class="embed"></div>
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
