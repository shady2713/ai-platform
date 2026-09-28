<script lang="ts" setup>
/**
 * 工具版本（D10 + X06）：显式命令创建草稿与发布，政策默认 DENY。
 *
 * 界面把规则写在明面上：政策缺省 DENY、来源 operation 必须已发布；
 * 写工具（X06）还要声明**业务幂等键参数**与**已发布的核对查询**，且政策不得 AUTO
 * （写调用必须人工确认）。绑定声明并入输出 schema 的保留键 write，发布失败原样展示后端原因。
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
/** 写工具绑定声明（X06）：幂等键必须是输入 schema 的必填字符串参数，核对查询必须是已发布 operation。 */
const writeBinding = ref({
  idempotencyParam: '',
  reconcileOperation: '',
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

/**
 * 写工具的绑定声明并入输出 schema 的保留键 write；读工具保持管理员填写的原文。
 * 缺项在提交前拦住（后端的发布期校验更严格：幂等键必须是必填字符串参数、核对查询必须已发布）。
 */
function outputSchemaForSubmit(): string {
  if (form.value.toolType !== 'WRITE') {
    return form.value.outputSchemaJson;
  }
  const idempotencyParam = writeBinding.value.idempotencyParam.trim();
  const reconcileOperation = writeBinding.value.reconcileOperation.trim();
  if (!idempotencyParam || !reconcileOperation) {
    throw new Error('写工具必须填写业务幂等键参数与核对查询');
  }
  const base: Record<string, unknown> = JSON.parse(
    form.value.outputSchemaJson || '{}',
  );
  base.write = {
    idempotencyParam,
    reconcileOperation,
    reconcileParam: idempotencyParam,
  };
  return JSON.stringify(base);
}

async function handleCreate() {
  if (!toolId.value) {
    return;
  }
  errorMessage.value = '';
  try {
    const outputSchemaJson = outputSchemaForSubmit();
    await createToolVersion({
      toolId: toolId.value,
      ...form.value,
      outputSchemaJson,
    });
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
          类型（WRITE 需声明幂等键与核对查询，且政策不得 AUTO）
          <select
            v-model="form.toolType"
            class="rounded border border-border p-1"
          >
            <option value="READ">READ（只读）</option>
            <option value="WRITE">WRITE（写操作）</option>
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
        <label v-if="form.toolType === 'WRITE'" class="flex flex-col">
          业务幂等键参数名（必须是输入 schema 的必填字符串参数）
          <input
            v-model="writeBinding.idempotencyParam"
            class="rounded border border-border p-1"
            placeholder="payment_no"
          />
        </label>
        <label v-if="form.toolType === 'WRITE'" class="flex flex-col">
          核对查询（同连接器已发布 operationKey）
          <input
            v-model="writeBinding.reconcileOperation"
            class="rounded border border-border p-1"
            placeholder="getPayment"
          />
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
