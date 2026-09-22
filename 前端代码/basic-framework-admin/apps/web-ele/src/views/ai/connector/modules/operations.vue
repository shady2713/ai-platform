<script lang="ts" setup>
/**
 * 接口管理（D10）：导入 OpenAPI 草稿 → 显式发布 → 按声明参数试跑。
 *
 * 三条边界在界面上直接体现：
 *   1) 只能从文档**导入**操作（没有手写 URL/方法/请求头的入口）；
 *   2) 草稿不可执行，必须显式"发布"；
 *   3) 试跑只能填该操作声明过的参数，不提供任意 SQL/脚本/请求体。
 */
import type { AiConnectorApi } from '#/api/ai/data';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  executeConnectorOperation,
  importConnectorOperations,
  listConnectorOperations,
  publishConnectorOperation,
} from '#/api/ai/data';
import { showSuccessMessage } from '#/utils/feedback';

const connectorId = ref<number>();
const document = ref('');
const operations = ref<AiConnectorApi.ConnectorOperation[]>([]);
const skipped = ref<string[]>([]);
const argumentsText = ref('{}');
const runResult = ref('');
const errorMessage = ref('');

function parseParameters(
  parameterJson?: string,
): Array<{ name: string; required?: boolean; type?: string }> {
  if (!parameterJson) {
    return [];
  }
  try {
    const parsed = JSON.parse(parameterJson) as Record<
      string,
      { required?: boolean; type?: string }
    >;
    return Object.entries(parsed).map(([name, spec]) => ({
      name,
      required: spec?.required,
      type: spec?.type,
    }));
  } catch {
    return [];
  }
}

/** 声明参数的可读标签（必填加 *；没有声明参数显示 —）。 */
function parameterLabel(operation: AiConnectorApi.ConnectorOperation): string {
  const parameters = parseParameters(operation.parameterJson);
  if (parameters.length === 0) {
    return '—';
  }
  return parameters
    .map((parameter) => `${parameter.name}${parameter.required ? '*' : ''}`)
    .join('、');
}

async function loadOperations() {
  if (!connectorId.value) {
    return;
  }
  operations.value = await listConnectorOperations(connectorId.value);
}

async function handleImport() {
  if (!connectorId.value || !document.value.trim()) {
    return;
  }
  const result = await importConnectorOperations(
    connectorId.value,
    document.value,
  );
  skipped.value = result.skipped ?? [];
  showSuccessMessage(`导入草稿 ${result.operationKeys?.length ?? 0} 个`);
  await loadOperations();
}

async function handlePublish(operation: AiConnectorApi.ConnectorOperation) {
  await publishConnectorOperation(operation.id, operation.version);
  showSuccessMessage(`已发布 ${operation.operationKey}`);
  await loadOperations();
}

async function handleExecute(operation: AiConnectorApi.ConnectorOperation) {
  if (!connectorId.value) {
    return;
  }
  errorMessage.value = '';
  runResult.value = '';
  let args: Record<string, unknown>;
  try {
    args = JSON.parse(argumentsText.value || '{}') as Record<string, unknown>;
  } catch {
    errorMessage.value = '参数必须是 JSON 对象';
    return;
  }
  try {
    const result = await executeConnectorOperation(
      connectorId.value,
      operation.operationKey,
      args,
    );
    // 截断与失败都有稳定原因码：界面必须原样展示，不能把 PARTIAL 当"完整"
    runResult.value = `结论：${result.status}${
      result.stoppedReason ? `（${result.stoppedReason}）` : ''
    }${result.detailCode ? ` 原因码：${result.detailCode}` : ''} 条目：${
      result.itemCount ?? 0
    }`;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '执行失败';
  }
}

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData<AiConnectorApi.Connector>();
    connectorId.value = data?.id;
    document.value = '';
    skipped.value = [];
    runResult.value = '';
    errorMessage.value = '';
    await loadOperations();
  },
});
</script>

<template>
  <Modal title="接口（operation）管理" class="w-[860px]">
    <div class="flex flex-col gap-3">
      <div>
        <p class="mb-1 text-sm font-medium">
          导入 OpenAPI 文档（只接受文档内 GET/POST 操作）
        </p>
        <textarea
          v-model="document"
          class="h-24 w-full rounded border border-border p-2 text-xs"
          placeholder="粘贴 OpenAPI 3.0 JSON 文本"
        ></textarea>
        <button
          class="mt-1 text-sm text-blue-600"
          type="button"
          @click="handleImport"
        >
          导入为草稿
        </button>
      </div>
      <ul v-if="skipped.length > 0" class="text-xs text-amber-600">
        <li v-for="reason in skipped" :key="reason">已跳过：{{ reason }}</li>
      </ul>
      <table class="w-full text-xs">
        <thead>
          <tr class="text-left">
            <th>操作</th>
            <th>方法</th>
            <th>路径</th>
            <th>状态</th>
            <th>声明参数</th>
            <th>命令</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="operation in operations" :key="operation.id">
            <td>{{ operation.operationKey }}</td>
            <td>{{ operation.httpMethod }}</td>
            <td>{{ operation.pathTemplate }}</td>
            <td>{{ operation.status === 'PUBLISHED' ? '已发布' : '草稿' }}</td>
            <td>{{ parameterLabel(operation) }}</td>
            <td class="whitespace-nowrap">
              <button
                v-if="operation.status !== 'PUBLISHED'"
                class="mr-2 text-blue-600"
                type="button"
                @click="handlePublish(operation)"
              >
                发布
              </button>
              <button
                class="text-blue-600 disabled:text-gray-400"
                :disabled="operation.status !== 'PUBLISHED'"
                type="button"
                @click="handleExecute(operation)"
              >
                试跑
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div>
        <p class="mb-1 text-sm font-medium">试跑参数（只允许声明过的参数名）</p>
        <textarea
          v-model="argumentsText"
          class="h-16 w-full rounded border border-border p-2 text-xs"
          placeholder="{'region':'EAST'}"
        ></textarea>
        <p v-if="runResult" class="mt-1 text-xs text-green-700">
          {{ runResult }}
        </p>
        <p v-if="errorMessage" class="mt-1 text-xs text-red-600">
          {{ errorMessage }}
        </p>
      </div>
    </div>
  </Modal>
</template>
