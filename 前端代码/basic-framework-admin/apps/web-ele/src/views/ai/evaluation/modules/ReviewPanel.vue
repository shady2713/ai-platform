<script lang="ts" setup>
import type { PanelFeedback } from '../data';

/**
 * 人工复核面板（Q05）：只对 `reviewStatus === 'PENDING'` 的结果给出通过/否决。
 *
 * 复核会改写判定与结果摘要（服务端动作），因此提交成功后父页面会重新读取运行详情与
 * 各面板数据；没有 `ai:eval:review` 权限时不显示按钮，只给说明（接口同样会拒绝）。
 */
import type { AiEvalApi } from '#/api/ai/evaluation';

import { ref, watch } from 'vue';

import {
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { getResultPage, reviewResult } from '#/api/ai/evaluation';

import { buildResultQuery, verdictRows, verdictSummary } from '../analysis';
import {
  AI_EVAL_PERMISSIONS,
  describeFailureCode,
  describeResultStatus,
  describeReviewStatus,
  describeSeverity,
  isReviewPending,
  RESULT_STATUS_OPTIONS,
  resultTagType,
} from '../data';

const props = defineProps<{
  canReview: boolean;
  refreshKey: number;
  run?: AiEvalApi.Run;
}>();

const emit = defineEmits<{
  (e: 'clearFeedback' | 'reviewed'): void;
  (e: 'feedback', payload: PanelFeedback): void;
}>();

const rows = ref<AiEvalApi.Result[]>([]);
const reviewStatus = ref<string>();
const pageNo = ref(1);
const pageSize = ref(20);
const total = ref(0);
const loading = ref(false);
const reviewOpen = ref(false);
const reviewTarget = ref<AiEvalApi.Result>();
const reviewApprove = ref(true);
const reviewNote = ref('');
const reviewRunning = ref(false);

async function refreshRows(): Promise<void> {
  const runId = props.run?.id;
  if (runId === undefined) {
    rows.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  try {
    const page = await getResultPage(
      buildResultQuery({
        pageNo: pageNo.value,
        pageSize: pageSize.value,
        runId,
        status: reviewStatus.value,
      }),
    );
    rows.value = page.list;
    total.value = page.total;
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `读取评测结果失败：请确认当前账号有 ${AI_EVAL_PERMISSIONS.query} 权限`,
    });
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

// 运行切换（回到第 1 页）或父页面要求刷新（复核后）时重新读取
let loadedRunId: number | undefined;
watch(
  () => [props.run?.id, props.refreshKey] as const,
  () => {
    if (props.run?.id !== loadedRunId) {
      loadedRunId = props.run?.id;
      pageNo.value = 1;
    }
    void refreshRows();
  },
  { immediate: true },
);

function handleSearch(): void {
  pageNo.value = 1;
  void refreshRows();
}

function handlePageChange(next: number): void {
  pageNo.value = next;
  void refreshRows();
}

function openReview(row: AiEvalApi.Result, approve: boolean): void {
  emit('clearFeedback');
  reviewTarget.value = row;
  reviewApprove.value = approve;
  reviewNote.value = '';
  reviewOpen.value = true;
}

async function confirmReview(): Promise<void> {
  const target = reviewTarget.value;
  if (target === undefined) {
    return;
  }
  reviewRunning.value = true;
  emit('clearFeedback');
  try {
    const note = reviewNote.value.trim();
    await reviewResult(
      note === ''
        ? { approve: reviewApprove.value, resultId: target.id }
        : { approve: reviewApprove.value, note, resultId: target.id },
    );
    emit('feedback', {
      kind: 'notice',
      message: `已${reviewApprove.value ? '通过' : '否决'} ${target.caseKey} 的复核：判定与结果摘要随复核更新`,
    });
    reviewOpen.value = false;
    reviewTarget.value = undefined;
    await refreshRows();
    // 判定与计数都变了：让父页面重新读运行详情并刷新其它面板
    emit('reviewed');
  } catch {
    emit('feedback', {
      kind: 'error',
      message: `复核失败：只有等待复核(PENDING)的结果可以复核，且需要 ${AI_EVAL_PERMISSIONS.review} 权限`,
    });
  } finally {
    reviewRunning.value = false;
  }
}
</script>

<template>
  <ElCard class="mb-4" header="人工复核（只对等待复核的结果生效）">
    <ElEmpty
      v-if="run === undefined"
      description="请先在运行列表点「查看结果」选择运行"
    />
    <template v-else>
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <span>判定过滤</span>
        <ElSelect
          v-model="reviewStatus"
          clearable
          data-testid="ai-eval-result-status"
          placeholder="全部判定"
          style="width: 240px"
          @change="handleSearch"
        >
          <ElOption
            v-for="item in RESULT_STATUS_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <ElButton
          data-testid="ai-eval-result-refresh"
          :loading="loading"
          @click="handleSearch"
        >
          刷新结果
        </ElButton>
        <span class="text-gray-500">
          共 {{ total }} 例（过滤与分页由服务端执行）
        </span>
      </div>
      <p
        v-if="!canReview"
        class="mb-3 text-gray-500"
        data-testid="ai-eval-review-hint"
      >
        当前账号没有
        {{ AI_EVAL_PERMISSIONS.review }}
        权限：可以看待复核结果与判定明细，但不能通过/否决（接口同样会拒绝）。
      </p>
      <ElEmpty v-if="rows.length === 0" description="没有符合条件的结果" />
      <ElTable
        v-else
        data-testid="ai-eval-results-table"
        :data="rows"
        size="small"
      >
        <ElTableColumn label="样例标识" prop="caseKey" min-width="140" />
        <ElTableColumn label="级别" min-width="120">
          <template #default="{ row }">
            {{ describeSeverity(row.severity) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="判定" min-width="220">
          <template #default="{ row }">
            <ElTag size="small" :type="resultTagType(row.status)">
              {{ describeResultStatus(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="复核状态" width="110">
          <template #default="{ row }">
            {{ describeReviewStatus(row.reviewStatus) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="失败码" min-width="150">
          <template #default="{ row }">
            {{ describeFailureCode(row.failureCode) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="判定摘要" min-width="240">
          <template #default="{ row }">{{ verdictSummary(row) }}</template>
        </ElTableColumn>
        <ElTableColumn fixed="right" label="操作" width="170">
          <template #default="{ row }">
            <template v-if="canReview && isReviewPending(row.reviewStatus)">
              <ElButton link type="success" @click="openReview(row, true)">
                通过
              </ElButton>
              <ElButton link type="danger" @click="openReview(row, false)">
                否决
              </ElButton>
            </template>
            <span v-else class="text-xs text-gray-500">
              {{
                isReviewPending(row.reviewStatus)
                  ? '无复核权限'
                  : '不在复核队列'
              }}
            </span>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElPagination
        class="mt-3 justify-end"
        data-testid="ai-eval-result-pagination"
        :current-page="pageNo"
        :page-size="pageSize"
        :total="total"
        @current-change="handlePageChange"
      />
    </template>

    <ElDialog v-model="reviewOpen" title="人工复核" width="620px">
      <div v-if="reviewTarget">
        <p>
          样例：{{ reviewTarget.caseKey }}（{{
            describeSeverity(reviewTarget.severity)
          }}）
        </p>
        <p>
          当前判定：{{ describeResultStatus(reviewTarget.status) }} ·
          复核状态：{{ describeReviewStatus(reviewTarget.reviewStatus) }}
        </p>
        <p>判定摘要：{{ verdictSummary(reviewTarget) }}</p>
        <p class="mb-3">
          {{
            reviewApprove ? '通过后该结果计入通过' : '否决后该结果计为失败'
          }}；复核会重算结果摘要。
        </p>
        <ElTable :data="verdictRows(reviewTarget.verdictJson)" size="small">
          <ElTableColumn label="序号" prop="index" width="70" />
          <ElTableColumn label="规则" prop="kindLabel" min-width="110" />
          <ElTableColumn label="路径" prop="path" min-width="110" />
          <ElTableColumn label="结果" width="80">
            <template #default="{ row }">
              {{ row.passed ? '通过' : '未通过' }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="期望" prop="expected" min-width="120" />
          <ElTableColumn label="实际" prop="observed" min-width="120" />
          <ElTableColumn label="说明" prop="message" min-width="160" />
        </ElTable>
        <ElInput
          v-model="reviewNote"
          class="mt-3"
          data-testid="ai-eval-review-note"
          placeholder="复核备注（可选，最多 512 字）"
          type="textarea"
        />
      </div>
      <template #footer>
        <ElButton @click="reviewOpen = false">取消</ElButton>
        <ElButton
          data-testid="ai-eval-review-confirm"
          :loading="reviewRunning"
          :type="reviewApprove ? 'success' : 'danger'"
          @click="confirmReview"
        >
          {{ reviewApprove ? '确认通过复核' : '确认否决复核' }}
        </ElButton>
      </template>
    </ElDialog>
  </ElCard>
</template>
