<script lang="ts" setup>
/**
 * 语义版本管理（D10）：创建草稿 → 验证（漂移给出原因）→ 发布（发布前再次确认可执行范围）。
 *
 * 界面上"不可发布"永远带原因（缺列/类型不兼容），不把漂移静默成"稍后重试"。
 */
import type { AiDatasetApi } from '#/api/ai/data';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  createDatasetVersion,
  getDatasetVersionPage,
  publishDatasetVersion,
  verifyDatasetVersion,
} from '#/api/ai/data';
import { showSuccessMessage } from '#/utils/feedback';

import { describeVerification } from '../data';

const datasetId = ref<number>();
const versions = ref<AiDatasetApi.DatasetVersion[]>([]);
const definitionJson = ref('');
const message = ref('');
const errorMessage = ref('');

async function loadVersions() {
  if (!datasetId.value) {
    return;
  }
  const page = await getDatasetVersionPage(datasetId.value, {
    pageNo: 1,
    pageSize: 20,
  });
  versions.value = page.list ?? [];
}

async function handleCreate() {
  if (!datasetId.value || !definitionJson.value.trim()) {
    errorMessage.value = '请填写语义定义（JSON）';
    return;
  }
  errorMessage.value = '';
  await createDatasetVersion(datasetId.value, definitionJson.value);
  showSuccessMessage('已创建语义版本草稿');
  await loadVersions();
}

async function handleVerify(version: AiDatasetApi.DatasetVersion) {
  const result = await verifyDatasetVersion(version.id, version.version);
  message.value = describeVerification(result);
  await loadVersions();
}

async function handlePublish(version: AiDatasetApi.DatasetVersion) {
  try {
    const result = await publishDatasetVersion(version.id, version.version);
    message.value = `已发布版本 ${result.versionNo}`;
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
    const data = modalApi.getData<AiDatasetApi.Dataset>();
    datasetId.value = data?.id;
    definitionJson.value = '';
    message.value = '';
    errorMessage.value = '';
    await loadVersions();
  },
});
</script>

<template>
  <Modal title="语义版本" class="w-[820px]">
    <div class="flex flex-col gap-3">
      <div>
        <p class="mb-1 text-sm font-medium">创建语义版本草稿</p>
        <textarea
          v-model="definitionJson"
          class="h-28 w-full rounded border border-border p-2 text-xs"
        ></textarea>
        <p class="text-xs text-muted-foreground">
          示例：grain（粒度）+
          fields（字段：name/sourceColumn/type/unit/visibility）+ metrics +
          dimensions； 这是声明式语义定义，不是 SQL。
        </p>
        <button
          class="mt-1 text-sm text-blue-600"
          type="button"
          @click="handleCreate"
        >
          创建草稿
        </button>
      </div>
      <table class="w-full text-xs">
        <thead>
          <tr class="text-left">
            <th>版本</th>
            <th>状态</th>
            <th>验证</th>
            <th>漂移结论</th>
            <th>命令</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="version in versions" :key="version.id">
            <td>v{{ version.versionNo }}</td>
            <td>{{ version.status === 'PUBLISHED' ? '已发布' : '草稿' }}</td>
            <td>{{ version.verificationStatus }}</td>
            <td>{{ version.driftJson || '—' }}</td>
            <td class="whitespace-nowrap">
              <button
                class="mr-2 text-blue-600 disabled:text-gray-400"
                :disabled="version.status === 'PUBLISHED'"
                type="button"
                @click="handleVerify(version)"
              >
                验证
              </button>
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
