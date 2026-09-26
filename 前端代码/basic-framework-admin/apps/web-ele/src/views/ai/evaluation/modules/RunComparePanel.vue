<script lang="ts" setup>
import type { RunDiffRow } from '../analysis';
import type { PanelFeedback } from '../data';

/**
 * 评测运行与用例级比较面板（Q05）：套件 → 运行列表 → 选两个运行做用例级 diff。
 *
 * 差异按 caseKey 对齐：状态变化、`caseDigest` 是否变化、失败码变化、判定（verdict）变化；
 * 只有一侧存在的样例单独标出（两次冻结内容不同）。运行冻结的 `suiteDigest`、修订号与
 * 计数一并展示，计数口径见失败分类面板。
 */
import type { AiEvalApi } from '#/api/ai/evaluation';

import { computed, ref, watch } from 'vue';

import {
  ElButton,
  ElCard,
  ElEmpty,
  ElPagination,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { getRun, getRunPage, listResults, startRun } from '#/api/ai/evaluation';

import { compareRuns, diffHeadline, frozenCaseCount } from '../analysis';
import {
  AI_EVAL_PERMISSIONS,
  changeText,
  describeResultStatus,
  describeRunStatus,
  describeSeverity,
  digestText,
} from '../data';

const props = defineProps<{
  canRun: boolean;
  contextKey: number;
  refreshKey: number;
  suite?: AiEvalApi.Suite;
}>();

const emit = defineEmits<{
  (e: 'clearFeedback'): void;
  (e: 'feedback', payload: PanelFeedback): void;
  (e: 'selectRun', runId: number): void;
}>();

const runs = ref<AiEvalApi.Run[]>([]);
const runPageNo = ref(1);
const runPageSize = ref(20);
const runTotal = ref(0);
const runStarting = ref(false);
const baseRunId = ref<number>();
const targetRunId = ref<number>();
const comparing = ref(false);
const compareBase = ref<AiEvalApi.Run>();
const compareTarget = ref<AiEvalApi.Run>();
const diffs = ref<RunDiffRow[]>([]);

const baseRun = computed(() =>
  runs.value.find((item) => item.id === baseRunId.value),
);
const targetRun = computed(() =>
  runs.value.find((item) => item.id === targetRunId.value),
);

async function refreshRuns(): Promise<void> {
  const suiteId = props.suite?.id;
  if (suiteId === undefined) {
    runs.value = [];
    runTotal.value = 0;
    return;
  }
  try {
    const page = await getRunPage({
      pageNo: runPageNo.value,
      pageSize: runPageSize.value,
      suiteId,
    });
    runs.value = page.list;
    runTotal.value = page.total;
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `读取评测运行失败：请确认当前账号有 ${AI_EVAL_PERMISSIONS.query} 权限`,
    });
    runs.value = [];
    runTotal.value = 0;
  }
}

/** 清掉比较选择（切换套件时必须清，避免拿另一个套件的运行做 diff） */
function resetCompare(): void {
  baseRunId.value = undefined;
  targetRunId.value = undefined;
  compareBase.value = undefined;
  compareTarget.value = undefined;
  diffs.value = [];
}

// 套件切换或父页面要求刷新（开始评测/复核等）时重新读取；只有一个信号，避免重复请求
let loadedSuiteId: number | undefined;
watch(
  () => [props.suite?.id, props.refreshKey] as const,
  () => {
    if (props.suite?.id !== loadedSuiteId) {
      loadedSuiteId = props.suite?.id;
      runPageNo.value = 1;
      resetCompare();
    }
    void refreshRuns();
  },
  { immediate: true },
);

// 父页面点名清空运行上下文（重新选套件/切换应用）时：回第 1 页并清掉比较选择；
// 刷新（refreshKey）只重读列表，不清比较选择，避免复核/开始评测后丢掉已选的 A/B。
watch(
  () => props.contextKey,
  () => {
    runPageNo.value = 1;
    resetCompare();
  },
);

function handleRunPageChange(next: number): void {
  runPageNo.value = next;
  void refreshRuns();
}

function markBase(row: AiEvalApi.Run): void {
  baseRunId.value = row.id;
  compareBase.value = undefined;
  compareTarget.value = undefined;
  diffs.value = [];
  emit('feedback', {
    kind: 'notice',
    message: `已把运行 ${row.id} 设为基准运行`,
  });
}

function markTarget(row: AiEvalApi.Run): void {
  targetRunId.value = row.id;
  compareBase.value = undefined;
  compareTarget.value = undefined;
  diffs.value = [];
  emit('feedback', {
    kind: 'notice',
    message: `已把运行 ${row.id} 设为对比运行`,
  });
}

/** 两个运行的用例级 diff：两侧运行详情与结果都重新读，避免拿旧计数与旧判定 */
async function compare(): Promise<void> {
  const baseId = baseRunId.value;
  const targetId = targetRunId.value;
  if (baseId === undefined || targetId === undefined) {
    emit('feedback', {
      kind: 'error',
      message: '请先在运行列表里选择基准运行与对比运行',
    });
    return;
  }
  if (baseId === targetId) {
    emit('feedback', {
      kind: 'error',
      message: '基准运行与对比运行不能是同一个运行',
    });
    return;
  }
  comparing.value = true;
  emit('clearFeedback');
  try {
    const [base, target, baseResults, targetResults] = await Promise.all([
      getRun(baseId),
      getRun(targetId),
      listResults(baseId),
      listResults(targetId),
    ]);
    compareBase.value = base;
    compareTarget.value = target;
    diffs.value = compareRuns(baseResults, targetResults);
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `比较失败：请确认两个运行都存在且当前账号有 ${AI_EVAL_PERMISSIONS.query} 权限`,
    });
  } finally {
    comparing.value = false;
  }
}

async function startEvaluation(): Promise<void> {
  const suiteId = props.suite?.id;
  if (suiteId === undefined) {
    emit('feedback', { kind: 'error', message: '请先选择套件' });
    return;
  }
  runStarting.value = true;
  emit('clearFeedback');
  try {
    const runId = await startRun(suiteId);
    emit('feedback', {
      kind: 'notice',
      message: `已开始评测：运行 ${runId}（逐例走真实运行服务，判定由确定性规则给出）`,
    });
    runPageNo.value = 1;
    await refreshRuns();
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `开始评测失败：套件必须有样例，且需要 ${AI_EVAL_PERMISSIONS.run} 权限`,
    });
  } finally {
    runStarting.value = false;
  }
}

/** 选定运行：把运行编号交给父页面（运行详情与结果由结果/复核/报告面板各取所需） */
function openRunResults(row: AiEvalApi.Run): void {
  emit('clearFeedback');
  emit('selectRun', row.id);
}
</script>

<template>
  <ElCard class="mb-4" header="评测运行与用例级比较">
    <div class="mb-3 flex flex-wrap items-center gap-3">
      <ElButton
        v-if="canRun"
        data-testid="ai-eval-run-start"
        :loading="runStarting"
        type="primary"
        @click="startEvaluation"
      >
        开始评测
      </ElButton>
      <ElButton
        data-testid="ai-eval-compare"
        :loading="comparing"
        @click="compare"
      >
        比较两个运行
      </ElButton>
      <ElTag v-if="baseRun" data-testid="ai-eval-base-tag" type="info">
        基准：运行 {{ baseRun.id }}
      </ElTag>
      <ElTag v-if="targetRun" data-testid="ai-eval-target-tag" type="info">
        对比：运行 {{ targetRun.id }}
      </ElTag>
    </div>
    <p v-if="!canRun" class="mb-3 text-gray-500" data-testid="ai-eval-run-hint">
      当前账号没有
      {{ AI_EVAL_PERMISSIONS.run }} 权限：可以查看运行与结果，但不能发起评测。
    </p>
    <ElEmpty v-if="runs.length === 0" description="该套件还没有评测运行" />
    <ElTable v-else data-testid="ai-eval-run-table" :data="runs" size="small">
      <ElTableColumn label="运行" width="80">
        <template #default="{ row }">{{ row.id }}</template>
      </ElTableColumn>
      <ElTableColumn label="状态" min-width="170">
        <template #default="{ row }">
          {{ describeRunStatus(row.status) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="冻结修订 / 套件摘要" min-width="200">
        <template #default="{ row }">
          修订 {{ row.suiteRevision ?? '未知' }} ·
          {{ digestText(row.suiteDigest) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="冻结样例快照" width="130">
        <template #default="{ row }">
          {{ frozenCaseCount(row.summaryJson) ?? '未记录' }} 例
        </template>
      </ElTableColumn>
      <ElTableColumn
        label="计数（通过 / 失败含待复核 / 错误 / 总数）"
        min-width="260"
      >
        <template #default="{ row }">
          {{ row.passedCount }} / {{ row.failedCount }} / {{ row.errorCount }} /
          {{ row.caseTotal }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="开始时间" min-width="170">
        <template #default="{ row }">
          {{ row.startedTime ?? '未记录' }}
        </template>
      </ElTableColumn>
      <ElTableColumn fixed="right" label="操作" width="220">
        <template #default="{ row }">
          <ElButton link type="primary" @click="openRunResults(row)">
            查看结果
          </ElButton>
          <ElButton link type="info" @click="markBase(row)">设为基准</ElButton>
          <ElButton link type="info" @click="markTarget(row)">
            设为对比
          </ElButton>
        </template>
      </ElTableColumn>
    </ElTable>
    <ElPagination
      class="mt-3 justify-end"
      data-testid="ai-eval-run-pagination"
      :current-page="runPageNo"
      :page-size="runPageSize"
      :total="runTotal"
      @current-change="handleRunPageChange"
    />
    <div
      v-if="compareBase && compareTarget"
      class="mt-3 text-sm"
      data-testid="ai-eval-compare-head"
    >
      <p>
        基准运行 {{ compareBase.id }}：修订
        {{ compareBase.suiteRevision ?? '未知' }} · 套件摘要
        {{ digestText(compareBase.suiteDigest) }} · 冻结快照
        {{ frozenCaseCount(compareBase.summaryJson) ?? '未记录' }} 例 · 通过
        {{ compareBase.passedCount }} / 失败
        {{ compareBase.failedCount }}（含待复核） / 错误
        {{ compareBase.errorCount }} / 共 {{ compareBase.caseTotal }}
      </p>
      <p>
        对比运行 {{ compareTarget.id }}：修订
        {{ compareTarget.suiteRevision ?? '未知' }} · 套件摘要
        {{ digestText(compareTarget.suiteDigest) }} · 冻结快照
        {{ frozenCaseCount(compareTarget.summaryJson) ?? '未记录' }} 例 · 通过
        {{ compareTarget.passedCount }} / 失败
        {{ compareTarget.failedCount }}（含待复核） / 错误
        {{ compareTarget.errorCount }} / 共
        {{ compareTarget.caseTotal }}
      </p>
      <p data-testid="ai-eval-diff-headline">{{ diffHeadline(diffs) }}</p>
    </div>
    <ElTable
      v-if="diffs.length > 0"
      class="mt-3"
      data-testid="ai-eval-diff-table"
      :data="diffs"
      size="small"
    >
      <ElTableColumn label="样例标识" prop="caseKey" min-width="140" />
      <ElTableColumn label="级别" min-width="120">
        <template #default="{ row }">
          {{ describeSeverity(row.severity) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="基准判定" min-width="180">
        <template #default="{ row }">
          {{ row.baseStatus ? describeResultStatus(row.baseStatus) : '缺失' }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="对比判定" min-width="180">
        <template #default="{ row }">
          {{
            row.targetStatus ? describeResultStatus(row.targetStatus) : '缺失'
          }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="状态变化" width="100">
        <template #default="{ row }">
          {{ changeText(row.statusChanged) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="样例摘要变化" width="130">
        <template #default="{ row }">
          {{ changeText(row.caseDigestChanged) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="失败码变化" width="110">
        <template #default="{ row }">
          {{ changeText(row.failureCodeChanged) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="判定变化" width="110">
        <template #default="{ row }">
          {{ changeText(row.verdictChanged) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="说明" min-width="260">
        <template #default="{ row }">{{ row.note }}</template>
      </ElTableColumn>
    </ElTable>
  </ElCard>
</template>
