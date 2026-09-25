<script lang="ts" setup>
/**
 * Chat 集成控制面页（C09，菜单 4110）。
 *
 * 页面回答三个问题：**嵌到哪里、怎么换票、能力到哪一版**。
 * 只展示真实存在的能力：嵌入入口（C05）、嵌入桥协议（C06/C07）、业务上下文与宿主事件（C08）；
 * 未实现的（分享/发布等）不放按钮。生成的接入片段**不含真实票据**，宿主后端换票步骤逐条写明。
 */
import type { ChatDisplayMode } from './data';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElForm,
  ElFormItem,
  ElOption,
  ElSelect,
  ElTag,
} from 'element-plus';

import {
  buildIntegrationSnippet,
  CAPABILITIES,
  getEmbedBasePath,
  MODE_OPTIONS,
  readEmbedBasePathFromEnv,
  TICKET_HELP,
} from './data';

const appCode = ref('');
const mode = ref<ChatDisplayMode>('inline');
const embedBasePath = ref('');
const copied = ref('');

const snippet = computed(() =>
  buildIntegrationSnippet({
    appCode: appCode.value.trim() || 'your-app-code',
    embedBasePath: embedBasePath.value.trim() || undefined,
    mode: mode.value,
  }),
);

async function copy(): Promise<void> {
  copied.value = '';
  try {
    await navigator.clipboard.writeText(snippet.value);
    copied.value = '已复制接入代码（其中票据是占位符，真实票据由宿主后端换取）';
  } catch {
    // 剪贴板不可用（权限/非安全上下文）：提示手动复制，不伪装成功
    copied.value = '当前环境无法写入剪贴板，请手动选择代码块复制';
  }
}

onMounted(() => {
  embedBasePath.value = readEmbedBasePathFromEnv();
  if (!embedBasePath.value) {
    embedBasePath.value = getEmbedBasePath();
  }
});
</script>

<template>
  <Page title="Chat 集成" description="嵌入入口、换票说明与 SDK 能力版本">
    <ElAlert
      :closable="false"
      class="mb-4"
      show-icon
      title="复制出去的代码里没有真实票据：票据由宿主后端用应用客户端凭据换取，浏览器只持有短期票据。"
      type="info"
    />
    <ElAlert
      v-if="copied"
      :closable="false"
      class="mb-4"
      :title="copied"
      type="success"
    />

    <ElCard class="mb-4" header="接入代码">
      <ElForm inline>
        <ElFormItem label="应用标识">
          <input
            v-model="appCode"
            class="w-60 rounded border px-2 py-1"
            placeholder="crm-portal"
          />
        </ElFormItem>
        <ElFormItem label="展示形态">
          <ElSelect v-model="mode" class="w-52">
            <ElOption
              v-for="option in MODE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="入口基址">
          <input
            v-model="embedBasePath"
            class="w-96 rounded border px-2 py-1"
            placeholder="/app-api/ai/v1/embed"
          />
        </ElFormItem>
        <ElFormItem>
          <ElButton type="primary" @click="copy">复制接入代码</ElButton>
        </ElFormItem>
      </ElForm>
      <div
        class="max-h-96 overflow-auto rounded bg-black/5 p-3 text-xs"
        data-testid="ai-chat-integration-snippet"
      >
        <pre>{{ snippet }}</pre>
      </div>
    </ElCard>

    <ElCard class="mb-4" header="后端换票说明（宿主职责）">
      <ol class="list-decimal pl-5 text-sm">
        <li v-for="(step, index) in TICKET_HELP" :key="index">{{ step }}</li>
      </ol>
      <p class="mt-2 text-xs text-muted-foreground">
        凭据只存在于宿主的服务端；浏览器、URL、localStorage 与日志都不得出现
        appSecret 或长期凭据。
      </p>
    </ElCard>

    <ElCard header="SDK 能力与版本">
      <ul class="space-y-1 text-sm">
        <li v-for="item in CAPABILITIES" :key="item.name">
          <ElTag size="small">{{ item.name }}</ElTag>
          <span class="ml-2">{{ item.detail }}</span>
        </li>
      </ul>
      <p class="mt-2 text-xs text-muted-foreground">
        自建 UI 路线：宿主只要实现「换票 +
        事件校验」，即可用自己的界面调用同一套桥协议； 平台的嵌入页与 SDK
        是可替换的默认实现，不是唯一入口。
      </p>
    </ElCard>
  </Page>
</template>
