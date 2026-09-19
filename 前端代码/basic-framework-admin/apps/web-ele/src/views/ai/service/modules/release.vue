<script lang="ts" setup>
/** 发布与评测（S05）：创建候选 → 记录评测 → 预检查 → 发布；发布结果与调试结果分开呈现。 */
import type { AiServiceApi } from '#/api/ai/service';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  checkReleaseReadiness,
  createReleaseCandidate,
  getService,
  listReleases,
  publishRelease,
  recordReleaseEvaluation,
} from '#/api/ai/service';
import { showSuccessMessage } from '#/utils/feedback';

import { describeReleaseContent, useEvaluationSchema } from '../data';

const emit = defineEmits(['success']);
const service = ref<AiServiceApi.Service>();
const releases = ref<AiServiceApi.Release[]>([]);
const candidate = ref<AiServiceApi.Release>();
const blockers = ref<string[]>([]);
const evaluation = ref({ caseCount: 10, notes: '', score: 90 });

const [EvaluationForm, evaluationApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-2',
  },
  layout: 'horizontal',
  schema: useEvaluationSchema(),
  showDefaultActions: false,
});

async function refresh(serviceId: number) {
  releases.value = await listReleases(serviceId);
  candidate.value = releases.value.find(
    (release) => release.status === 'CANDIDATE',
  );
  blockers.value = candidate.value
    ? await checkReleaseReadiness(candidate.value.id)
    : [];
}

async function handleCreateCandidate() {
  if (!service.value) {
    return;
  }
  await createReleaseCandidate(service.value.id, service.value.version);
  const detail = await getService(service.value.id);
  service.value = detail;
  await refresh(detail.id);
  showSuccessMessage('已创建发布候选（内容与端点配置版本已冻结）');
}

async function handleEvaluate() {
  if (!candidate.value) {
    return;
  }
  const { valid } = await evaluationApi.validate();
  if (!valid) {
    return;
  }
  const values = (await evaluationApi.getValues()) as {
    caseCount: number;
    notes?: string;
    score: number;
  };
  evaluation.value = {
    caseCount: values.caseCount,
    notes: values.notes ?? '',
    score: values.score,
  };
  await recordReleaseEvaluation(
    candidate.value.id,
    values.score,
    values.caseCount,
    values.notes,
  );
  if (service.value) {
    await refresh(service.value.id);
  }
  showSuccessMessage('评测结论已记录（通过与否由平台按冻结门槛判定）');
}

async function handlePublish() {
  if (!candidate.value || !service.value) {
    return;
  }
  await publishRelease(candidate.value.id, candidate.value.version);
  await refresh(service.value.id);
  emit('success');
  showSuccessMessage('发布成功：发布别名已切换（失败时当前生效版本不变）');
}

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      service.value = undefined;
      releases.value = [];
      candidate.value = undefined;
      blockers.value = [];
      return;
    }
    const row = modalApi.getData<AiServiceApi.Service>();
    if (!row?.id) {
      return;
    }
    const detail = await getService(row.id);
    service.value = detail;
    await refresh(detail.id);
  },
});
</script>

<template>
  <Modal class="w-[760px]" title="发布与评测">
    <section class="space-y-3 text-sm">
      <p class="text-muted-foreground">
        发布前必须同时满足：模型能力、资源绑定与当前授权、Schema
        完整性与评测证据。 任一项不满足都不会切换别名。
      </p>
      <div class="flex items-center gap-3">
        <button
          class="text-primary"
          type="button"
          :disabled="service?.status !== 'READY'"
          @click="handleCreateCandidate"
        >
          创建发布候选
        </button>
        <span class="text-xs text-muted-foreground">
          草稿状态：{{ service?.status ?? '-' }}（非 READY 不能创建候选）
        </span>
      </div>

      <div v-if="candidate" class="rounded border p-3">
        <h4 class="mb-1 font-medium">候选 v{{ candidate.releaseVersion }}</h4>
        <p class="text-xs text-muted-foreground">
          {{ describeReleaseContent(candidate) }}
        </p>
        <EvaluationForm class="mt-2" />
        <button class="mt-2 text-primary" type="button" @click="handleEvaluate">
          记录评测结论
        </button>
      </div>

      <div v-if="candidate" class="rounded border p-3">
        <h4 class="mb-1 font-medium">发布预检查</h4>
        <ul
          v-if="blockers.length > 0"
          class="list-disc pl-5 text-xs text-destructive"
        >
          <li v-for="blocker in blockers" :key="blocker">{{ blocker }}</li>
        </ul>
        <p v-else class="text-xs">全部通过，可以发布</p>
        <button
          class="mt-2 rounded bg-primary px-2 py-1 text-xs text-primary-foreground disabled:opacity-50"
          type="button"
          :disabled="blockers.length > 0"
          @click="handlePublish"
        >
          发布（切换别名）
        </button>
      </div>

      <div v-if="releases.length > 0" class="rounded border p-3">
        <h4 class="mb-1 font-medium">发布结果</h4>
        <ul class="space-y-1 text-xs">
          <li v-for="release in releases" :key="release.id">
            v{{ release.releaseVersion }} · {{ release.status }} · 门槛
            {{ release.evalThreshold }}
          </li>
        </ul>
      </div>
    </section>
  </Modal>
</template>
