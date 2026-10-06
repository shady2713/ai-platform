<script lang="ts" setup>
import type { PlanFormValues, SummaryFormValues } from './data';

/**
 * AI 查询计划页面（D05，菜单 4070）：填条件 → 提交 → 看结果。
 *
 * 这一域**没有列表接口**：计划是一次性调用（服务层不落库），摘要按数据集实时生成，
 * 所以页面形态是操作页而不是表格页——渲染一张假的"计划历史"表只会向用户承诺
 * 后端并不存在的能力。
 *
 * 页面纪律：
 * 1. 结果只有两种正常结果（PLAN / CLARIFICATION），都要照实渲染，澄清不是失败；
 * 2. 计划 JSON 只读展示，页面不提供任何 SQL 编辑面——后端也不接受 SQL；
 * 3. 两个端点各自独立授权，无权用户看不到提交入口。
 */
import type { AiQueryApi } from '#/api/ai/query';

import { ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import { createQueryPlan, getQueryDatasetSummary } from '#/api/ai/query';
import { showRequestError } from '#/utils/feedback';

import {
  AI_QUERY_PERMISSIONS,
  buildPlanRequest,
  buildSummaryParams,
  describePlanResult,
  formatJsonForRead,
  NO_SQL_NOTICE,
  usePlanFormSchema,
  useSummaryFormSchema,
} from './data';

const { hasAccessByCodes } = useAccess();
const canPlan = hasAccessByCodes([AI_QUERY_PERMISSIONS.plan]);
const canSummary = hasAccessByCodes([AI_QUERY_PERMISSIONS.summary]);

const [PlanForm, planFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: usePlanFormSchema(),
  showDefaultActions: false,
});

const [SummaryForm, summaryFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: useSummaryFormSchema(),
  showDefaultActions: false,
});

const planResult = ref<AiQueryApi.PlanResult>();
const summaryResult = ref<AiQueryApi.SummaryResult>();
const planFailure = ref('');
const summaryFailure = ref('');

async function handlePlan() {
  const { valid } = await planFormApi.validate();
  if (!valid) {
    return;
  }
  const values = (await planFormApi.getValues()) as PlanFormValues;
  // 提交前先清旧结果：后端失败时不能让上一份计划继续留在屏幕上冒充本次结论
  planResult.value = undefined;
  planFailure.value = '';
  try {
    planResult.value = await createQueryPlan(buildPlanRequest(values));
  } catch (error) {
    showRequestError(error, '查询计划生成失败');
    planFailure.value =
      '查询计划生成失败：未返回任何结果（后端拒绝或服务不可用）';
  }
}

async function handleSummary() {
  const { valid } = await summaryFormApi.validate();
  if (!valid) {
    return;
  }
  const values = (await summaryFormApi.getValues()) as SummaryFormValues;
  summaryResult.value = undefined;
  summaryFailure.value = '';
  try {
    summaryResult.value = await getQueryDatasetSummary(
      buildSummaryParams(values),
    );
  } catch (error) {
    showRequestError(error, '数据集摘要读取失败');
    summaryFailure.value = '数据集摘要读取失败：未返回任何摘要内容';
  }
}
</script>

<template>
  <Page auto-content-height>
    <div class="p-4">
      <h3 class="mb-1 text-base font-medium">AI 查询计划</h3>
      <p class="mb-1 text-xs text-muted-foreground">
        {{
          planResult
            ? describePlanResult(planResult)
            : '提交问题后由后端生成并校验计划；本接口不保存历史计划，刷新即失效。'
        }}
      </p>
      <p class="mb-3 text-xs text-muted-foreground">{{ NO_SQL_NOTICE }}</p>

      <section class="mb-4 rounded border p-3">
        <h4 class="mb-1 text-sm font-medium">生成查询计划</h4>
        <PlanForm v-if="canPlan" />
        <p v-else class="text-xs text-muted-foreground">
          无生成计划权限（ai:query:plan），请联系管理员授权。
        </p>
        <button
          v-if="canPlan"
          class="mt-2 rounded bg-primary px-3 py-1 text-xs text-primary-foreground"
          type="button"
          @click="handlePlan"
        >
          生成查询计划
        </button>

        <p
          v-if="planFailure"
          class="mt-2 text-xs text-destructive"
          data-testid="ai-query-plan-failure"
        >
          {{ planFailure }}
        </p>

        <template v-if="planResult">
          <p class="mt-2 text-xs">结果类型：{{ planResult.kind }}</p>
          <!-- 澄清不是失败：把追问、原因与候选原样交给用户选择 -->
          <div
            v-if="planResult.kind === 'CLARIFICATION'"
            class="mt-2 rounded bg-black/5 p-2 text-xs"
            data-testid="ai-query-clarification"
          >
            <p>{{ planResult.question }}</p>
            <p class="mt-1 text-muted-foreground">
              候选（只来自本次授权目录）：
            </p>
            <ul class="mt-1 list-disc pl-5">
              <li
                v-for="candidate in planResult.candidates ?? []"
                :key="candidate.code"
              >
                {{ candidate.label }}（{{ candidate.code }}）
              </li>
            </ul>
            <p
              v-if="(planResult.candidates ?? []).length === 0"
              class="mt-1 text-muted-foreground"
            >
              本次未返回候选：把问题里的口径说清楚后重试。
            </p>
          </div>
          <div
            v-else
            class="mt-2 rounded bg-black/5 p-2 text-xs"
            data-testid="ai-query-plan-json"
          >
            <p class="mb-1 text-muted-foreground">
              已校验计划（只读：页面不提供编辑入口，后端也不接受 SQL）
            </p>
            <pre class="max-h-96 overflow-auto whitespace-pre-wrap">{{
              formatJsonForRead(planResult.planJson)
            }}</pre>
          </div>
        </template>
      </section>

      <section class="rounded border p-3">
        <h4 class="mb-1 text-sm font-medium">模型可见的数据集摘要</h4>
        <p class="mb-2 text-xs text-muted-foreground">
          摘要就是模型能看到的全部信息，用来核对字段与语义元数据是否越界。
        </p>
        <SummaryForm v-if="canSummary" />
        <p v-else class="text-xs text-muted-foreground">
          无查看摘要权限（ai:query:summary），请联系管理员授权。
        </p>
        <button
          v-if="canSummary"
          class="mt-2 rounded border px-3 py-1 text-xs"
          type="button"
          @click="handleSummary"
        >
          查看数据集摘要
        </button>

        <p
          v-if="summaryFailure"
          class="mt-2 text-xs text-destructive"
          data-testid="ai-query-summary-failure"
        >
          {{ summaryFailure }}
        </p>
        <pre
          v-if="summaryResult"
          class="mt-2 max-h-96 overflow-auto whitespace-pre-wrap rounded bg-black/5 p-2 text-xs"
          data-testid="ai-query-summary-json"
        >
          {{ formatJsonForRead(summaryResult.summaryJson) }}
        </pre>
      </section>
    </div>
  </Page>
</template>
