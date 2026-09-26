<script lang="ts" setup>
/**
 * 用量与限额页面（Q03，菜单 4115 对应的页面；数据来自 Q02 的 `/ai/usage/**`）。
 *
 * 三件事必须可分：
 *  - **口径**：REPORTED（上游报告）/ ESTIMATED（平台估算）/ UNKNOWN（没有用量事实）分别标注，
 *    token 未知显示"未知"而不是 0（AT-060）；
 *  - **筛选**：应用 + 服务 + 时间窗都由**服务端**过滤后再分页，界面不做"取一页再自己筛"；
 *  - **权限**：读取需要 `ai:usage:query`，无权限时后端拒绝，界面按同一权限码给出提示。
 */
import type { AiUsageApi } from '#/api/ai/observability/usage';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { getApplicationPage } from '#/api/ai/application';
import {
  getQuotaActive,
  getUsagePage,
  getUsageServiceSummary,
  getUsageSummary,
} from '#/api/ai/observability/usage';

import {
  AI_USAGE_PERMISSIONS,
  describeUsageSource,
  formatDuration,
  formatTokens,
  isUnknownUsage,
  summaryHeadline,
  summaryRows,
  WINDOW_DAY_OPTIONS,
  windowRange,
} from './data';

const applications = ref<Array<{ appCode: string; id: number; name: string }>>(
  [],
);
const applicationId = ref<number>();
const serviceId = ref<number>();
const windowDays = ref(7);
const pageNo = ref(1);
const pageSize = ref(20);
const total = ref(0);
const rows = ref<AiUsageApi.UsageRow[]>([]);
const summary = ref<AiUsageApi.SourceSummary>();
const serviceSummary = ref<AiUsageApi.ServiceSummaryRow[]>([]);
const quotaActive = ref<number>();
const loading = ref(false);
const error = ref('');

const summaryRowList = computed(() => summaryRows(summary.value));

async function loadApplications(): Promise<void> {
  const page = await getApplicationPage({ pageNo: 1, pageSize: 100 });
  applications.value = page.list.map((item) => ({
    appCode: item.appCode,
    id: item.id,
    name: item.name,
  }));
  applicationId.value ??= applications.value[0]?.id;
}

async function refresh(): Promise<void> {
  if (applicationId.value === undefined) {
    return;
  }
  loading.value = true;
  error.value = '';
  const range = windowRange(windowDays.value);
  try {
    const [page, bySource, byService, active] = await Promise.all([
      getUsagePage({
        applicationId: applicationId.value,
        from: range.from,
        pageNo: pageNo.value,
        pageSize: pageSize.value,
        serviceId: serviceId.value,
        to: range.to,
      }),
      getUsageSummary({
        applicationId: applicationId.value,
        from: range.from,
        to: range.to,
      }),
      getUsageServiceSummary({
        applicationId: applicationId.value,
        from: range.from,
        to: range.to,
      }),
      getQuotaActive(applicationId.value),
    ]);
    rows.value = page.list;
    total.value = page.total;
    summary.value = bySource;
    serviceSummary.value = byService;
    quotaActive.value = active;
  } catch {
    // 失败不伪装成功：保留上一次数据并说明权限/参数要求
    error.value = `读取用量失败：请确认应用存在且当前账号有 ${AI_USAGE_PERMISSIONS.query} 权限`;
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

onMounted(async () => {
  try {
    await loadApplications();
    await refresh();
  } catch {
    error.value = `读取应用列表失败：请确认当前账号有 ai:application:query 权限`;
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
          data-testid="ai-usage-application"
          placeholder="选择应用"
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
        <ElSelect
          v-model="serviceId"
          clearable
          data-testid="ai-usage-service"
          placeholder="全部服务"
          style="width: 180px"
          @change="handleSearch"
        >
          <ElOption
            v-for="item in serviceSummary"
            :key="item.serviceId"
            :label="`服务 ${item.serviceId}`"
            :value="item.serviceId"
          />
        </ElSelect>
        <span>时间窗</span>
        <ElSelect
          v-model="windowDays"
          data-testid="ai-usage-window"
          style="width: 140px"
          @change="handleSearch"
        >
          <ElOption
            v-for="item in WINDOW_DAY_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <ElButton
          data-testid="ai-usage-refresh"
          :loading="loading"
          @click="handleSearch"
        >
          刷新
        </ElButton>
        <ElTag v-if="quotaActive !== undefined" type="info">
          当前有效占位 {{ quotaActive }}（未释放且未到期，到期占位不计入）
        </ElTag>
      </div>
      <ElAlert
        v-if="error"
        class="mt-3"
        data-testid="ai-usage-error"
        :title="error"
        type="error"
        :closable="false"
      />
    </ElCard>

    <ElCard class="mb-4" title="按计量来源汇总">
      <p data-testid="ai-usage-headline">{{ summaryHeadline(summary) }}</p>
      <ElEmpty
        v-if="summaryRowList.length === 0"
        description="该时间窗内没有计量记录"
      />
      <ElTable v-else :data="summaryRowList" size="small">
        <ElTableColumn label="来源" min-width="220">
          <template #default="{ row }">
            <ElTag
              :type="isUnknownUsage(row.usageSource) ? 'warning' : 'success'"
            >
              {{ describeUsageSource(row.usageSource) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="调用次数" prop="invocationCount" width="110" />
        <ElTableColumn label="输入 token" width="130">
          <template #default="{ row }">
            {{ formatTokens(row.inputTokens) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="输出 token" width="130">
          <template #default="{ row }">
            {{ formatTokens(row.outputTokens) }}
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElCard class="mb-4" title="用量明细">
      <ElTable :data="rows" data-testid="ai-usage-table" size="small">
        <ElTableColumn label="时间" min-width="170">
          <template #default="{ row }">{{ row.occurredAt ?? '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="调用标识" prop="invocationId" min-width="160" />
        <ElTableColumn label="服务" width="80">
          <template #default="{ row }">{{ row.serviceId ?? '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="运行" width="80">
          <template #default="{ row }">{{ row.runId ?? '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="模型" min-width="140">
          <template #default="{ row }">{{ row.modelRef ?? '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="端点引用" min-width="140">
          <template #default="{ row }">{{ row.endpointRef ?? '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="来源" min-width="150">
          <template #default="{ row }">
            {{ describeUsageSource(row.usageSource) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="结果" width="110">
          <template #default="{ row }">{{ row.status ?? '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="上游耗时" width="120">
          <template #default="{ row }">
            {{ formatDuration(row.durationMs) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="输入 token" width="120">
          <template #default="{ row }">
            {{ formatTokens(row.inputTokens) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="输出 token" width="120">
          <template #default="{ row }">
            {{ formatTokens(row.outputTokens) }}
          </template>
        </ElTableColumn>
      </ElTable>
      <ElPagination
        class="mt-3 justify-end"
        data-testid="ai-usage-pagination"
        :current-page="pageNo"
        :page-size="pageSize"
        :total="total"
        @current-change="handlePageChange"
      />
    </ElCard>

    <ElCard title="按服务汇总">
      <ElEmpty
        v-if="serviceSummary.length === 0"
        description="该时间窗内没有服务维度记录"
      />
      <ElTable v-else :data="serviceSummary" size="small">
        <ElTableColumn label="服务编号" prop="serviceId" width="120" />
        <ElTableColumn label="调用次数" prop="invocationCount" width="120" />
        <ElTableColumn label="输入 token" width="140">
          <template #default="{ row }">
            {{ formatTokens(row.inputTokens) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="输出 token" width="140">
          <template #default="{ row }">
            {{ formatTokens(row.outputTokens) }}
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </Page>
</template>
