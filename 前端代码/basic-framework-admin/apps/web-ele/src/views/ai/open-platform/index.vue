<script lang="ts" setup>
/**
 * 开放平台目录与在线调试（O08）。
 *
 * 目录只展示**已发布**的开放端点（未发布接口不出现）；每条给出鉴权与归属、限额与示例。
 * 在线调试走应用端通道：用应用客户端凭据换取短期受限票据，票据只驻留内存，
 * 凭据与令牌都不回显、不落盘；调试权限与真实身份一致。
 */
import type { AiOpenPlatformApi } from '#/api/ai/open-platform';

import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  callDebugEndpoint,
  findEndpoint,
  issueDebugTicket,
} from '#/api/ai/open-platform';

import {
  catalogEntries,
  catalogSummary,
  catalogTags,
  DEBUG_NOTICE,
  exampleForCopy,
  exampleLeaksCredential,
  useCatalogColumns,
  useDebugSchema,
} from './data';

const activeTag = ref<string>();
const selected = ref<AiOpenPlatformApi.Endpoint>();
const debugResult = ref<AiOpenPlatformApi.DebugCallResult>();
const debugFailure = ref('');
const ticketIssued = ref(false);

const entries = computed(() => catalogEntries(activeTag.value));
const summary = computed(() => catalogSummary(entries.value));
const columns = useCatalogColumns();

/** 示例里若出现疑似真实凭据，页面直接拒绝展示（复制出去的示例必须无真实 token） */
const requestExampleText = computed(() =>
  selected.value?.requestExample
    ? `请求：${selected.value.requestExample}`
    : '',
);
const responseExampleText = computed(() =>
  selected.value?.responseExample
    ? `响应：${selected.value.responseExample}`
    : '',
);

const exampleSafe = computed(
  () =>
    !exampleLeaksCredential(selected.value?.requestExample) &&
    !exampleLeaksCredential(selected.value?.responseExample),
);

const [DebugForm, debugFormApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-2',
  },
  layout: 'horizontal',
  schema: useDebugSchema(),
  showDefaultActions: false,
});

function handleSelect(endpoint: AiOpenPlatformApi.Endpoint) {
  selected.value = endpoint;
  debugResult.value = undefined;
  debugFailure.value = '';
  ticketIssued.value = false;
}

async function handleCopy(endpoint: AiOpenPlatformApi.Endpoint) {
  const text = exampleForCopy(endpoint);
  if (exampleLeaksCredential(text)) {
    debugFailure.value = '示例包含疑似真实凭据，已阻止复制';
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    // 剪贴板不可用（无权限或非安全上下文）：不静默失败，提示用户手动复制
    debugFailure.value = '剪贴板不可用，请手动复制示例';
  }
}

async function handleDebug() {
  const { valid } = await debugFormApi.validate();
  if (!valid) {
    return;
  }
  const values = (await debugFormApi.getValues()) as {
    appCode: string;
    appSecret: string;
    endpointId: string;
    externalUserId?: string;
    subjectType: string;
  };
  const endpoint = findEndpoint(values.endpointId);
  if (!endpoint) {
    debugFailure.value = '该接口未在目录中登记（未发布接口不提供调试）';
    return;
  }
  debugResult.value = undefined;
  debugFailure.value = '';
  ticketIssued.value = false;
  try {
    // 换票：凭据只在请求体里出现；票据只驻留内存
    const ticket = await issueDebugTicket(
      values.appCode,
      values.appSecret,
      values.subjectType,
      values.externalUserId,
    );
    ticketIssued.value = true;
    debugResult.value = await callDebugEndpoint(
      ticket.token,
      endpoint.method,
      endpoint.path,
    );
  } catch {
    // 失败只提示稳定结论：不回显凭据、令牌或上游正文
    debugFailure.value =
      '调试失败：请按错误码处理后重试（不返回假成功，也不回显凭据）';
  }
}
</script>

<template>
  <Page auto-content-height>
    <div class="p-4">
      <h3 class="mb-1 text-base font-medium">AI 开放平台</h3>
      <p class="mb-3 text-xs text-muted-foreground">
        目录只包含已发布的开放接口；规范来源见
        <code>docs/integrations/open-api/ai-open-api.json</code>。当前登记
        {{ summary.total }} 个接口（异步受理 {{ summary.asynchronous }} 个、SSE
        {{ summary.sse }} 个）。
      </p>

      <div class="mb-3 flex flex-wrap gap-2">
        <button
          class="rounded border px-2 py-1 text-xs"
          :class="{ 'bg-primary text-primary-foreground': !activeTag }"
          type="button"
          @click="activeTag = undefined"
        >
          全部
        </button>
        <button
          v-for="tag in catalogTags()"
          :key="tag"
          class="rounded border px-2 py-1 text-xs"
          :class="{ 'bg-primary text-primary-foreground': activeTag === tag }"
          type="button"
          @click="activeTag = tag"
        >
          {{ tag }}
        </button>
      </div>

      <table class="w-full text-left text-xs">
        <thead>
          <tr>
            <th v-for="column in columns" :key="String(column.field)">
              {{ column.title }}
            </th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="endpoint in entries"
            :key="endpoint.id"
            data-testid="catalog-row"
          >
            <td>{{ endpoint.tag }}</td>
            <td>{{ endpoint.method }}</td>
            <td>{{ endpoint.path }}</td>
            <td>{{ endpoint.summary }}</td>
            <td>
              {{
                endpoint.sse
                  ? 'SSE 事件流'
                  : endpoint.asynchronous
                    ? '异步受理'
                    : '同步'
              }}
            </td>
            <td>{{ endpoint.auth }}</td>
            <td>
              <button
                class="text-primary"
                type="button"
                @click="handleSelect(endpoint)"
              >
                查看详情
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <section v-if="selected" class="mt-4 rounded border p-3 text-xs">
        <h4 class="mb-1 font-medium">
          {{ selected.method }} {{ selected.path }}
        </h4>
        <p class="mb-2 text-muted-foreground">{{ selected.auth }}</p>
        <ul class="mb-2 list-disc pl-5">
          <li v-for="limit in selected.limits" :key="limit">{{ limit }}</li>
          <li v-if="selected.limits.length === 0">无额外限额</li>
        </ul>
        <div v-if="exampleSafe" class="space-y-1">
          <pre
            v-if="requestExampleText"
            class="whitespace-pre-wrap rounded bg-gray-50 p-2"
            v-text="requestExampleText"
          ></pre>
          <pre
            v-if="responseExampleText"
            class="whitespace-pre-wrap rounded bg-gray-50 p-2"
            v-text="responseExampleText"
          ></pre>
          <p v-if="selected.sse" class="text-muted-foreground">
            SSE：先鉴权再开流，心跳是注释（不推进
            seq），重放窗口过期请读运行进度与快照。
          </p>
          <p v-if="selected.asynchronous" class="text-muted-foreground">
            异步：受理后通过事件流或进度查询获取结果，不要重复提交（幂等键会拒绝同键异请求）。
          </p>
          <ul class="list-disc pl-5">
            <li v-for="error in selected.errors" :key="error">{{ error }}</li>
          </ul>
          <button
            class="text-primary"
            type="button"
            @click="handleCopy(selected)"
          >
            复制示例
          </button>
        </div>
        <p v-else class="text-destructive">
          示例包含疑似真实凭据，已阻止展示与复制。
        </p>
      </section>

      <section class="mt-4 rounded border p-3">
        <h4 class="mb-1 text-sm font-medium">在线调试</h4>
        <p class="mb-2 text-xs text-muted-foreground">{{ DEBUG_NOTICE }}</p>
        <DebugForm />
        <button
          class="rounded bg-primary px-3 py-1 text-xs text-primary-foreground"
          type="button"
          @click="handleDebug"
        >
          执行调试
        </button>
        <p v-if="ticketIssued" class="mt-2 text-xs text-muted-foreground">
          已换取短期受限票据（票据只驻留内存，页面刷新即失效）
        </p>
        <p v-if="debugFailure" class="mt-2 text-xs text-destructive">
          {{ debugFailure }}
        </p>
        <pre
          v-if="debugResult"
          class="mt-2 whitespace-pre-wrap rounded bg-gray-50 p-2 text-xs"
          data-testid="debug-result"
          v-text="debugResult.body"
        ></pre>
      </section>
    </div>
  </Page>
</template>
