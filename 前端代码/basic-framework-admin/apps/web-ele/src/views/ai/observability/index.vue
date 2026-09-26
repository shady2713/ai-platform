<script lang="ts" setup>
/**
 * 运行监控页面（Q03，菜单 4116 对应的页面）。
 *
 * 四个运维问题在一屏内回答：
 *  - **跑到哪一步**：运行状态 + 步数 + 事件序号，详情里给事件时间线；
 *  - **失败在哪**：任务状态与稳定失败原因码（不展开异常正文）；
 *  - **慢在哪里**：模型耗时来自用量账本实测，检索/业务 API 未单独计量则明确标注"未单独计量"；
 *  - **能不能重试**：可重试性来自服务端同一判据，重试按钮还需要 `ai:observability:retry`，
 *    确认时带上运行行版本（拿过期版本会被服务端拒绝）。
 */
import type { AiObservabilityApi } from '#/api/ai/observability';

import { computed, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { getApplicationPage } from '#/api/ai/application';
import {
  getRunDetail,
  getRunPage,
  getRunTimeline,
  retryRun,
} from '#/api/ai/observability';

import {
  AI_OBSERVABILITY_PERMISSIONS,
  buildRunQuery,
  canRetry,
  describeFailureReason,
  describeRunStatus,
  describeTaskStatus,
  retryBlockedText,
  RUN_STATUS_OPTIONS,
  SUBJECT_TYPE_OPTIONS,
  timingRows,
} from './data';

const { hasAccessByCodes } = useAccess();

const applications = ref<Array<{ appCode: string; id: number; name: string }>>(
  [],
);
const applicationId = ref<number>();
const serviceId = ref<number>();
const status = ref<string>();
const subjectType = ref<string>();
const range = ref<[string, string]>();
const pageNo = ref(1);
const pageSize = ref(20);
const total = ref(0);
const rows = ref<AiObservabilityApi.RunRow[]>([]);
const loading = ref(false);
const error = ref('');
const notice = ref('');

const detail = ref<AiObservabilityApi.RunDetail>();
const detailOpen = ref(false);
const timeline = ref<AiObservabilityApi.TimelineEvent[]>([]);

const retryDetail = ref<AiObservabilityApi.RunDetail>();
const retryOpen = ref(false);
const retryRunning = ref(false);

const timing = computed(() => timingRows(detail.value?.timing));
const canUseRetry = computed(() =>
  hasAccessByCodes([AI_OBSERVABILITY_PERMISSIONS.retry]),
);

async function loadApplications(): Promise<void> {
  const page = await getApplicationPage({ pageNo: 1, pageSize: 100 });
  applications.value = page.list.map((item) => ({
    appCode: item.appCode,
    id: item.id,
    name: item.name,
  }));
}

async function refresh(): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    const page = await getRunPage(
      buildRunQuery({
        applicationId: applicationId.value,
        pageNo: pageNo.value,
        pageSize: pageSize.value,
        range: range.value,
        serviceId: serviceId.value,
        status: status.value,
        subjectType: subjectType.value,
      }),
    );
    rows.value = page.list;
    total.value = page.total;
  } catch {
    error.value = `读取运行列表失败：请确认当前账号有 ${AI_OBSERVABILITY_PERMISSIONS.query} 权限`;
  } finally {
    loading.value = false;
  }
}

function handleSearch(): void {
  pageNo.value = 1;
  void refresh();
}

function handlePageChange(next: number): void {
  pageNo.value = next;
  void refresh();
}

async function openDetail(row: AiObservabilityApi.RunRow): Promise<void> {
  error.value = '';
  notice.value = '';
  try {
    const [loaded, events] = await Promise.all([
      getRunDetail(row.runId),
      getRunTimeline({ limit: 100, runId: row.runId }),
    ]);
    detail.value = loaded;
    timeline.value = events;
    detailOpen.value = true;
  } catch {
    error.value = '读取运行详情失败：请确认当前账号有查看权限';
  }
}

async function openRetry(row: AiObservabilityApi.RunRow): Promise<void> {
  error.value = '';
  notice.value = '';
  try {
    retryDetail.value = await getRunDetail(row.runId);
    retryOpen.value = true;
  } catch {
    error.value = '读取运行详情失败：重试需要先拿到运行行版本';
  }
}

async function confirmRetry(): Promise<void> {
  const target = retryDetail.value;
  if (!target) {
    return;
  }
  retryRunning.value = true;
  error.value = '';
  try {
    await retryRun({ runId: target.runId, version: target.runVersion });
    notice.value = `已重试 ${target.runKey}：任务回到排队，运行回到已受理`;
    retryOpen.value = false;
    retryDetail.value = undefined;
    await refresh();
  } catch {
    error.value =
      '重试被拒绝：只有失败且未结束的任务可以重试（结果未知或仍在执行的任务会拒绝，请核对后新建运行）';
  } finally {
    retryRunning.value = false;
  }
}

onMounted(async () => {
  try {
    await loadApplications();
    await refresh();
  } catch {
    error.value =
      '读取应用列表失败：请确认当前账号有 ai:application:query 权限';
  }
});
</script>

<template>
  <Page auto-content-height>
    <ElCard class="mb-4">
      <div class="flex flex-wrap items-center gap-3">
        <span>应用</span>
        <ElSelect
          v-model="applicationId"
          data-testid="ai-observability-application"
          clearable
          placeholder="全部应用"
          style="width: 200px"
          @change="handleSearch"
        >
          <ElOption
            v-for="item in applications"
            :key="item.id"
            :label="`${item.name}（${item.appCode}）`"
            :value="item.id"
          />
        </ElSelect>
        <span>服务编号</span>
        <ElInputNumber
          v-model="serviceId"
          data-testid="ai-observability-service"
          :min="1"
          placeholder="全部服务"
          style="width: 150px"
          @change="handleSearch"
        />
        <span>状态</span>
        <ElSelect
          v-model="status"
          data-testid="ai-observability-status"
          clearable
          placeholder="全部状态"
          style="width: 150px"
          @change="handleSearch"
        >
          <ElOption
            v-for="item in RUN_STATUS_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <span>主体</span>
        <ElSelect
          v-model="subjectType"
          data-testid="ai-observability-subject"
          clearable
          placeholder="全部主体"
          style="width: 150px"
          @change="handleSearch"
        >
          <ElOption
            v-for="item in SUBJECT_TYPE_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <span>受理时间</span>
        <ElDatePicker
          v-model="range"
          data-testid="ai-observability-range"
          end-placeholder="结束（不含）"
          range-separator="至"
          start-placeholder="开始（含）"
          style="width: 320px"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          @change="handleSearch"
        />
        <ElButton
          data-testid="ai-observability-refresh"
          :loading="loading"
          @click="handleSearch"
        >
          刷新
        </ElButton>
      </div>
      <ElAlert
        v-if="error"
        class="mt-3"
        data-testid="ai-observability-error"
        :closable="false"
        :title="error"
        type="error"
      />
      <ElAlert
        v-if="notice"
        class="mt-3"
        data-testid="ai-observability-notice"
        :closable="false"
        :title="notice"
        type="success"
      />
      <p v-if="!canUseRetry" class="mt-3 text-gray-500">
        当前账号没有
        {{ AI_OBSERVABILITY_PERMISSIONS.retry }}
        权限：可以查看运行与用量，但不能发起重试。
      </p>
    </ElCard>

    <ElCard title="运行列表">
      <ElEmpty
        v-if="rows.length === 0"
        description="没有符合条件的运行（筛选条件由服务端执行）"
      />
      <ElTable
        v-else
        data-testid="ai-observability-table"
        :data="rows"
        size="small"
      >
        <ElTableColumn label="运行" min-width="150">
          <template #default="{ row }">{{ row.runKey }}</template>
        </ElTableColumn>
        <ElTableColumn label="应用 / 服务" min-width="130">
          <template #default="{ row }">
            {{ row.applicationId }} / {{ row.serviceId }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="主体" width="90">
          <template #default="{ row }">{{ row.subjectType }}</template>
        </ElTableColumn>
        <ElTableColumn label="运行状态" min-width="110">
          <template #default="{ row }">
            {{ describeRunStatus(row.status) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="步数 / 事件序号" width="130">
          <template #default="{ row }">
            {{ row.stepCount ?? 0 }} / {{ row.latestSeq ?? 0 }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="任务" min-width="180">
          <template #default="{ row }">
            {{ describeTaskStatus(row.taskStatus) }}（尝试
            {{ row.attemptCount ?? 0 }} 次）
          </template>
        </ElTableColumn>
        <ElTableColumn label="失败原因" min-width="180">
          <template #default="{ row }">
            {{ describeFailureReason(row.lastErrorCode) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="可重试" min-width="200">
          <template #default="{ row }">
            <ElTag v-if="canRetry(row)" type="success">可重试</ElTag>
            <ElTag v-else type="info">{{ retryBlockedText(row) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="受理时间" min-width="170">
          <template #default="{ row }">{{ row.createTime ?? '-' }}</template>
        </ElTableColumn>
        <ElTableColumn fixed="right" label="操作" width="170">
          <template #default="{ row }">
            <ElButton link type="primary" @click="openDetail(row)">
              详情
            </ElButton>
            <ElButton
              v-if="canUseRetry && canRetry(row)"
              link
              type="warning"
              @click="openRetry(row)"
            >
              重试
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElPagination
        class="mt-3 justify-end"
        data-testid="ai-observability-pagination"
        :current-page="pageNo"
        :page-size="pageSize"
        :total="total"
        @current-change="handlePageChange"
      />
    </ElCard>

    <ElDialog v-model="detailOpen" title="运行详情" width="760px">
      <div v-if="detail">
        <p>
          运行：{{ detail.runKey }}（{{ describeRunStatus(detail.status) }}）
        </p>
        <p>
          服务 {{ detail.serviceId }}，发布版本 {{ detail.releaseId }}，模型端点
          {{ detail.modelEndpointId }}（配置修订
          {{ detail.endpointConfigRevision }}）
        </p>
        <p>
          任务：{{ describeTaskStatus(detail.taskStatus) }}，尝试
          {{ detail.attemptCount ?? 0 }} 次，失败原因
          {{ describeFailureReason(detail.lastErrorCode) }}
        </p>
        <p>
          结果引用：会话 {{ detail.conversationId ?? '-' }}，助手消息
          {{ detail.resultMessageId ?? '-' }}，摘要
          {{ detail.resultDigest ?? '-' }}
        </p>
        <p data-testid="ai-observability-retry-state">
          可重试：{{ detail.retryable ? '是' : retryBlockedText(detail) }}
        </p>
        <ElTable
          data-testid="ai-observability-timing"
          :data="timing"
          size="small"
        >
          <ElTableColumn label="阶段" prop="label" width="110" />
          <ElTableColumn label="耗时" prop="value" width="140" />
          <ElTableColumn label="说明" prop="note" />
        </ElTable>
        <p class="mt-3">
          事件时间线（{{ timeline.length }} 条；正文不在列表返回）
        </p>
        <ElEmpty v-if="timeline.length === 0" description="还没有事件" />
        <ElTable v-else :data="timeline" size="small">
          <ElTableColumn label="序号" prop="seq" width="80" />
          <ElTableColumn label="状态" prop="status" width="150" />
          <ElTableColumn label="结果块" width="160">
            <template #default="{ row }">{{ row.blockType ?? '无' }}</template>
          </ElTableColumn>
          <ElTableColumn label="带正文" width="100">
            <template #default="{ row }">
              {{ row.blockPresent ? '是（受权接口读取）' : '否' }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="时间" min-width="170">
            <template #default="{ row }">{{ row.createTime ?? '-' }}</template>
          </ElTableColumn>
        </ElTable>
      </div>
    </ElDialog>

    <ElDialog v-model="retryOpen" title="人工重试" width="520px">
      <div v-if="retryDetail">
        <p>运行：{{ retryDetail.runKey }}</p>
        <p>
          当前状态：{{ describeRunStatus(retryDetail.status) }}；任务：{{
            describeTaskStatus(retryDetail.taskStatus)
          }}
        </p>
        <p>
          运行行版本：{{
            retryDetail.runVersion
          }}（拿过期版本重试会被服务端拒绝）
        </p>
        <ElAlert
          :closable="false"
          title="重试会重置尝试次数并让任务重新排队；结果未知的任务不能重试，请核对后新建运行。"
          type="warning"
        />
      </div>
      <template #footer>
        <ElButton @click="retryOpen = false">取消</ElButton>
        <ElButton
          data-testid="ai-observability-retry-confirm"
          :loading="retryRunning"
          type="primary"
          @click="confirmRetry"
        >
          确认重试
        </ElButton>
      </template>
    </ElDialog>
  </Page>
</template>
