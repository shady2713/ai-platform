<script lang="ts" setup>
/**
 * 版本历史与回退（S05）：显示当前生效版本、历史版本与回退影响。
 *
 * 回退只切换发布别名：已固定版本的会话沿用原版本，资源授权与停用状态始终按当前值判定。
 */
import type { AiServiceApi } from '#/api/ai/service';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  listReleaseEvaluations,
  listReleases,
  rollbackRelease,
} from '#/api/ai/service';
import { showSuccessMessage } from '#/utils/feedback';

import {
  currentRelease,
  describeReleaseContent,
  describeRollbackImpact,
  RELEASE_STATUS_LABELS,
} from '../data';

const emit = defineEmits(['success']);
const releases = ref<AiServiceApi.Release[]>([]);
const evaluations = ref<AiServiceApi.ReleaseEvaluation[]>([]);
const selectedId = ref<number>();

const active = computed(() => currentRelease(releases.value));
const target = computed(() =>
  releases.value.find((release) => release.id === selectedId.value),
);
const impact = computed(() =>
  selectedId.value
    ? describeRollbackImpact(releases.value, selectedId.value)
    : '',
);

async function load(serviceId: number) {
  releases.value = await listReleases(serviceId);
  selectedId.value = undefined;
  evaluations.value = [];
}

async function handleSelect(release: AiServiceApi.Release) {
  selectedId.value = release.id;
  evaluations.value = await listReleaseEvaluations(release.id);
}

async function handleRollback() {
  if (!target.value) {
    return;
  }
  await rollbackRelease(target.value.id, target.value.version);
  const serviceId = target.value.serviceId;
  await load(serviceId);
  emit('success');
  showSuccessMessage('回退完成：发布别名已切回历史版本（只影响后续运行）');
}

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      releases.value = [];
      evaluations.value = [];
      selectedId.value = undefined;
      return;
    }
    const row = modalApi.getData<AiServiceApi.Service>();
    if (row?.id) {
      await load(row.id);
    }
  },
});
</script>

<template>
  <Modal class="w-[860px]" title="版本历史与回退">
    <section class="space-y-3 text-sm">
      <p data-testid="current-version">
        当前生效版本：
        <strong v-if="active">v{{ active.releaseVersion }}</strong>
        <span v-else>无（服务未上线）</span>
      </p>

      <table class="w-full text-left text-xs">
        <thead>
          <tr>
            <th>版本</th>
            <th>状态</th>
            <th>内容</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="release in releases" :key="release.id">
            <td>v{{ release.releaseVersion }}</td>
            <td>
              {{ RELEASE_STATUS_LABELS[release.status] ?? release.status }}
            </td>
            <td class="text-muted-foreground">
              {{ describeReleaseContent(release) }}
            </td>
            <td>
              <button
                class="text-primary"
                type="button"
                @click="handleSelect(release)"
              >
                查看影响
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="target" class="rounded border p-3">
        <h4 class="mb-1 font-medium">
          回退到 v{{ target.releaseVersion }} 的影响
        </h4>
        <p class="text-xs text-muted-foreground">{{ impact }}</p>
        <button
          v-if="target.status !== 'ACTIVE'"
          class="mt-2 rounded bg-primary px-2 py-1 text-xs text-primary-foreground"
          type="button"
          @click="handleRollback"
        >
          回退到该版本
        </button>
        <p v-else class="mt-2 text-xs">该版本已是当前生效版本</p>
        <ul v-if="evaluations.length > 0" class="mt-2 text-xs">
          <li v-for="item in evaluations" :key="item.id">
            得分 {{ item.score }} / 门槛 {{ item.threshold }} ·
            {{ item.passed ? '通过' : '未通过' }} · 用例 {{ item.caseCount }}
          </li>
        </ul>
      </div>
    </section>
  </Modal>
</template>
