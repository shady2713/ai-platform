<script lang="ts" setup>
import type { PanelFeedback } from '../data';

/**
 * 失败分类面板（Q05）：对选定运行的**全部结果**按级别、规则 kind、错误码聚合。
 *
 * 三件事必须看清：
 *  - 计数口径：通过只计 PASSED，FAILED 与待复核计入失败，ERROR 单独计错，并做求和核对；
 *  - 判定 JSON 无法解析的结果单独计数（不会被算成通过）；
 *  - 未执行（ERROR）与待复核（REVIEW_REQUIRED）在页面上的文案都明确不是"通过"。
 */
import type { AiEvalApi } from '#/api/ai/evaluation';

import { computed, ref, watch } from 'vue';

import {
  ElAlert,
  ElCard,
  ElEmpty,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { listResults } from '#/api/ai/evaluation';

import {
  classifyFailures,
  countingCheckText,
  countingSummary,
  pendingReviewText,
} from '../analysis';
import { AI_EVAL_PERMISSIONS, describeRunStatus, resultTagType } from '../data';

const props = defineProps<{
  refreshKey: number;
  run?: AiEvalApi.Run;
}>();

const emit = defineEmits<{
  (e: 'feedback', payload: PanelFeedback): void;
}>();

const allResults = ref<AiEvalApi.Result[]>([]);

const classification = computed(() => classifyFailures(allResults.value));
const countingText = computed(() => countingSummary(props.run));
const countCheckText = computed(() => countingCheckText(props.run));
const panelTitle = computed(() => {
  const run = props.run;
  return run
    ? `失败分类：运行 ${run.id}（${describeRunStatus(run.status)}）`
    : '失败分类：请先选择运行';
});

async function loadResults(): Promise<void> {
  const runId = props.run?.id;
  if (runId === undefined) {
    allResults.value = [];
    return;
  }
  try {
    // 分类必须覆盖整个运行：用 list（按冻结顺序返回全部结果），不用分页
    allResults.value = await listResults(runId);
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `读取评测结果失败：请确认当前账号有 ${AI_EVAL_PERMISSIONS.query} 权限`,
    });
    allResults.value = [];
  }
}

// 运行切换或父页面要求刷新（复核改写判定后）时重新读取全部结果
watch(
  () => [props.run?.id, props.refreshKey],
  () => {
    void loadResults();
  },
  { immediate: true },
);
</script>

<template>
  <ElCard class="mb-4" :header="panelTitle">
    <ElEmpty
      v-if="run === undefined"
      description="请先在运行列表点「查看结果」选择运行"
    />
    <template v-else>
      <p data-testid="ai-eval-counting">{{ countingText }}</p>
      <p data-testid="ai-eval-count-check">{{ countCheckText }}</p>
      <p data-testid="ai-eval-pending-review">
        {{ pendingReviewText(classification.pendingReview) }}
      </p>
      <ElAlert
        v-if="classification.unparsedVerdicts > 0"
        class="mt-2"
        data-testid="ai-eval-unparsed"
        :closable="false"
        :title="`有 ${classification.unparsedVerdicts} 例判定 JSON 无法解析：这些结果不会被算成通过，请以逐例结果为准`"
        type="warning"
      />
      <p class="mt-3">
        按级别聚合（共 {{ classification.total }} 例，全部结果参与统计）
      </p>
      <ElTable
        data-testid="ai-eval-failure-severity"
        :data="classification.bySeverity"
        size="small"
      >
        <ElTableColumn label="级别" prop="label" min-width="150" />
        <ElTableColumn label="用例数" prop="total" width="90" />
        <ElTableColumn label="通过" prop="passed" width="80" />
        <ElTableColumn label="失败（含待复核）" prop="failed" width="140" />
        <ElTableColumn label="待复核" prop="pendingReview" width="90" />
        <ElTableColumn label="未执行" prop="error" width="90" />
        <ElTableColumn label="未通过规则数" prop="failedRules" width="120" />
      </ElTable>
      <p class="mt-3">按规则类型聚合（来自 verdictJson，含未通过明细）</p>
      <ElEmpty
        v-if="classification.byKind.length === 0"
        description="该运行没有可解析的判定明细"
      />
      <ElTable
        v-else
        data-testid="ai-eval-failure-kind"
        :data="classification.byKind"
        size="small"
      >
        <ElTableColumn label="规则类型" prop="label" min-width="150" />
        <ElTableColumn label="核验条数" prop="checked" width="110" />
        <ElTableColumn label="未通过条数" prop="failed" width="110" />
      </ElTable>
      <p class="mt-3">按未能执行的错误码聚合</p>
      <ElEmpty
        v-if="classification.byFailureCode.length === 0"
        description="没有未能执行的结果"
      />
      <ElTable
        v-else
        data-testid="ai-eval-failure-code"
        :data="classification.byFailureCode"
        size="small"
      >
        <ElTableColumn label="错误码" prop="code" min-width="150" />
        <ElTableColumn label="含义" prop="label" min-width="240" />
        <ElTableColumn label="条数" prop="count" width="80" />
      </ElTable>
      <p class="mt-3">判定分布</p>
      <ElTable
        data-testid="ai-eval-failure-status"
        :data="classification.byStatus"
        size="small"
      >
        <ElTableColumn label="判定" min-width="260">
          <template #default="{ row }">
            <ElTag size="small" :type="resultTagType(row.status)">
              {{ row.label }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="条数" prop="count" width="80" />
      </ElTable>
    </template>
  </ElCard>
</template>
