<script lang="ts" setup>
/**
 * 工具版本（D10）：显式命令创建草稿与发布，政策默认 DENY。
 *
 * 界面把三条规则写在明面上：政策缺省 DENY、首期只允许发布 READ、
 * 来源 operation 必须已发布——发布失败会原样展示后端原因。
 */
import type { AiToolApi } from '#/api/ai/data';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  createToolVersion,
  getToolVersionPage,
  publishToolVersion,
} from '#/api/ai/data';
import { showSuccessMessage } from '#/utils/feedback';

import { describePolicy } from '../data';

const toolId = ref<number>();
const versions = ref<AiToolApi.ToolVersion[]>([]);
const form = ref({
  inputSchemaJson: '{"region":{"type":"string","required":true}}',
  outputSchemaJson: '{"columns":[]}',
  policy: 'DENY',
  sourceKind: 'HTTP_OPERATION',
  sourceRef: '',
  toolType: 'READ',
});
const message = ref('');
const errorMessage = ref('');

async function loadVersions() {
  if (!toolId.value) {
    return;
  }
  const page = await getToolVersionPage(toolId.value, {
    pageNo: 1,
    pageSize: 20,
  });
  versions.value = page.list ?? [];
}

async function handleCreate() {
  if (!toolId.value) {
    return;
  }
  errorMessage.value = '';
  try {
    await createToolVersion({ toolId: toolId.value, ...form.value });
    showSuccessMessage('已创建版本草稿（政策按所选值）');
    await loadVersions();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '创建失败';
  }
}

async function handlePublish(version: AiToolApi.ToolVersion) {
  errorMessage.value = '';
  message.value = '';
  try {
    await publishToolVersion(version.id, version.version);
    message.value = `已发布版本 ${version.versionNo}`;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '发布失败';
  }
  await loadVersions();
}

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData<AiToolApi.Tool>();
    toolId.value = data?.id;
    message.value = '';
    errorMessage.value = '';
    await loadVersions();
  },
});
</script>

<template>
  <Modal title="工具版本与执行政策" class="w-[820px]">
    <div class="flex flex-col gap-3">
      <div class="grid grid-cols-2 gap-2 text-xs">
        <label class="flex flex-col">
          类型（首期只允许发布 READ）
          <select
            v-model="form.toolType"
            class="rounded border border-border p-1"
          >
            <option value="READ">READ（只读）</option>
            <option value="WRITE">WRITE（不可发布）</option>
          </select>
        </label>
        <label class="flex flex-col">
          执行政策（缺省 DENY）
          <select
            v-model="form.policy"
            class="rounded border border-border p-1"
          >
            <option value="DENY">DENY（禁止执行）</option>
            <option value="CONFIRM">CONFIRM（需人工确认）</option>
            <option value="AUTO">AUTO（自动执行）</option>
          </select>
        </label>
        <label class="col-span-2 flex flex-col">
          来源操作（必须是已发布的 operationKey）
          <input
            v-model="form.sourceRef"
            class="rounded border border-border p-1"
            placeholder="getOrders"
          />
        </label>
        <label class="col-span-2 flex flex-col">
          输入 schema（声明参数面）
          <textarea
            v-model="form.inputSchemaJson"
            class="h-16 rounded border border-border p-1"
          ></textarea>
        </label>
        <label class="col-span-2 flex flex-col">
          输出 schema
          <textarea
            v-model="form.outputSchemaJson"
            class="h-12 rounded border border-border p-1"
          ></textarea>
        </label>
      </div>
      <button class="text-sm text-blue-600" type="button" @click="handleCreate">
        创建版本草稿
      </button>
      <table class="w-full text-xs">
        <thead>
          <tr class="text-left">
            <th>版本</th>
            <th>状态</th>
            <th>政策</th>
            <th>来源</th>
            <th>命令</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="version in versions" :key="version.id">
            <td>v{{ version.versionNo }}</td>
            <td>{{ version.status === 'PUBLISHED' ? '已发布' : '草稿' }}</td>
            <td>{{ describePolicy(version.policy, version.toolType) }}</td>
            <td>{{ version.sourceRef }}</td>
            <td>
              <button
                class="text-blue-600 disabled:text-gray-400"
                :disabled="version.status === 'PUBLISHED'"
                type="button"
                @click="handlePublish(version)"
              >
                发布
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="message" class="text-xs text-green-700">{{ message }}</p>
      <p v-if="errorMessage" class="text-xs text-red-600">{{ errorMessage }}</p>
    </div>
  </Modal>
</template>
