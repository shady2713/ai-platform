<script lang="ts" setup>
import type { AiModelEndpointApi } from '#/api/ai/model-endpoint';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  getModelEndpointCapabilities,
  getModelEndpointProbeResults,
  probeModelEndpoint,
} from '#/api/ai/model-endpoint';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { PROBE_KIND_LABELS, PROBE_STATUS_OPTIONS } from '../data';

const endpoint = ref<AiModelEndpointApi.ModelEndpoint>();
const results = ref<AiModelEndpointApi.ModelProbeResult[]>([]);
const overview = ref<AiModelEndpointApi.CapabilityOverview>();
const probing = ref(false);

function statusLabel(status: string) {
  return (
    PROBE_STATUS_OPTIONS.find((item) => item.value === status)?.label ?? status
  );
}

async function loadResults(id: number) {
  results.value = await getModelEndpointProbeResults(id);
  overview.value = await getModelEndpointCapabilities(id);
}

async function handleProbe() {
  if (!endpoint.value) {
    return;
  }
  probing.value = true;
  try {
    results.value = await probeModelEndpoint(endpoint.value.id);
    overview.value = await getModelEndpointCapabilities(endpoint.value.id);
    showSuccessMessage($t('ui.actionMessage.operationSuccess'));
  } finally {
    probing.value = false;
  }
}

const [Modal, modalApi] = useVbenModal({
  footer: false,
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      endpoint.value = undefined;
      results.value = [];
      overview.value = undefined;
      return;
    }
    endpoint.value = modalApi.getData<AiModelEndpointApi.ModelEndpoint>();
    modalApi.setState({ title: `能力探测：${endpoint.value?.name ?? ''}` });
    if (endpoint.value) {
      await loadResults(endpoint.value.id);
    }
  },
});
</script>

<template>
  <Modal class="w-[720px]">
    <div class="flex flex-col gap-3">
      <div class="text-sm text-gray-500">
        探测会对上游发起真实调用；探测失败不会被启用状态掩盖，结论按类型记录。
      </div>
      <table class="w-full text-sm">
        <thead>
          <tr class="text-left text-gray-500">
            <th class="py-1">探测类型</th>
            <th class="py-1">结论</th>
            <th class="py-1">明细</th>
            <th class="py-1">耗时</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in results" :key="row.probeKind" class="border-t">
            <td class="py-1">
              {{ PROBE_KIND_LABELS[row.probeKind] ?? row.probeKind }}
            </td>
            <td class="py-1">{{ statusLabel(row.status) }}</td>
            <td class="py-1">
              <span v-if="row.detailCode">{{ row.detailCode }}</span>
              <span v-else-if="row.embeddingDimension">
                维度 {{ row.embeddingDimension }}
              </span>
              <span v-else>-</span>
            </td>
            <td class="py-1">{{ row.latencyMs }} ms</td>
          </tr>
        </tbody>
      </table>
      <div v-if="overview" class="text-sm">
        可发布范围：{{ overview.publishable.join('、') || '（无）' }}；声明：{{
          overview.declared.join('、') || '（无）'
        }}
      </div>
      <div class="flex justify-end gap-2">
        <button type="button" class="btn" @click="modalApi.close()">
          关闭
        </button>
        <button
          type="button"
          class="btn btn-primary"
          :disabled="probing"
          @click="handleProbe"
        >
          {{ probing ? '探测中…' : '重新探测' }}
        </button>
      </div>
    </div>
  </Modal>
</template>
