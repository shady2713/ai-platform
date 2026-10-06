<script lang="ts" setup>
/**
 * 跨源合并结果页面（V97 菜单 4133/4134；后端 `AiCrossSourceMergeController`）。
 *
 * 后端**没有列表接口**——只有两个按执行键查询的端点，因此这是「按执行键查询」的
 * 操作页，不做成列表页：没有列表就不画表格头，也不给"翻页"这种不存在的动作。
 *
 * 交互按「先问口径再取数」两步走，这也是后端单独开一个只读口径端点的理由：
 * 先调 `/integrity` 拿 `state`，`COMPLETE` 才去调结果端点。口径为 `WITHHELD` 时
 * 本页从头到尾没有经手过任何金额——数字在服务端就从未被序列化过。
 *
 * fail-closed 是本卡的命门，因此页面上有两道独立的闸门：
 *  1. **请求闸门**（`canFetchAmounts`）：口径不放行就不发结果请求；
 *  2. **渲染闸门**（`shouldRenderAmounts`）：结果自身的口径不放行就不渲染任何数字。
 * 第 2 道不能省：万一某次响应在 `WITHHELD` 下带回了数字，页面也不跟着显示。
 */
import type { CrossSourceQueryForm } from './data';

import type { AiCrossSourceApi } from '#/api/ai/cross-source';

import { computed, reactive, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  getCrossSourceIntegrity,
  getCrossSourceResult,
} from '#/api/ai/cross-source';

import {
  AI_CROSS_SOURCE_PERMISSIONS,
  canFetchAmounts,
  CROSS_SOURCE_CALLER_ROLE_OPTIONS,
  CROSS_SOURCE_SUBJECT_TYPE_OPTIONS,
  formatAmount,
  formatConsistencyAsOf,
  formatSkew,
  integrityReasonText,
  integrityStateCode,
  integrityStateText,
  integrityTone,
  shouldRenderAmounts,
  sourceRows,
  toMergeQuery,
  validateQuery,
  withheldNotice,
} from './data';

const form = reactive<CrossSourceQueryForm>({
  applicationId: undefined,
  callerRoles: [],
  executionKey: '',
  externalUserId: '',
  previouslySeenRoles: [],
  subjectType: 'USER',
});

const integrity = ref<AiCrossSourceApi.Integrity>();
const result = ref<AiCrossSourceApi.MergeResult>();
const probeLoading = ref(false);
const fetchLoading = ref(false);
const formError = ref('');
const probeError = ref('');
const fetchError = ref('');

/** 请求闸门：只有口径明确 COMPLETE 才放行取数 */
const fetchAllowed = computed(() => canFetchAmounts(integrity.value));
/**
 * 渲染闸门：只看**将要渲染的那份结果自身**的口径。
 * 不复用上面的请求闸门——两者判的是不同对象：一个是"能不能去问"，
 * 一个是"问回来的东西能不能画"，混用会让后一次判定覆盖前一次。
 */
const amountsVisible = computed(() =>
  shouldRenderAmounts(result.value?.integrity),
);
const sourceList = computed(() => sourceRows(result.value));

// 口径与结果都绑定在这一组查询参数上：改了任一字段就一并作废。
// 否则上一组参数放行的口径会被拿来放行新参数——换个执行键就能凭旧口径取数，
// 这正是 fail-closed 要堵的那类缺口。
watch(form, () => {
  integrity.value = undefined;
  result.value = undefined;
});

/** 第一步：只问口径。本端点的响应里不可能出现任何金额。 */
async function handleProbe(): Promise<void> {
  const invalid = validateQuery(form);
  formError.value = invalid;
  probeError.value = '';
  fetchError.value = '';
  const query = toMergeQuery(form);
  if (invalid || query === undefined) {
    return;
  }
  probeLoading.value = true;
  try {
    integrity.value = await getCrossSourceIntegrity(query);
    result.value = undefined;
  } catch {
    // 失败不伪装成功：清空口径，请求闸门随之关闭
    integrity.value = undefined;
    probeError.value = `读取授权完整性口径失败：请确认执行键存在，且当前账号有 ${AI_CROSS_SOURCE_PERMISSIONS.integrity} 权限`;
  } finally {
    probeLoading.value = false;
  }
}

/** 第二步：口径放行后才去取数字。 */
async function handleFetch(): Promise<void> {
  fetchError.value = '';
  // 闸门在函数里再判一次：按钮禁用只是界面提示，不能是唯一的一道闸
  if (!canFetchAmounts(integrity.value)) {
    fetchError.value = '请先读取口径，且口径为「完整」时才允许取数';
    return;
  }
  // 取数用的是**当前表单**而不是查口径时那份：中途改过执行键/主体/角色就意味着
  // 手上这份口径已经不对应了，这里重新校验并作废，而不是拿旧口径去取新参数。
  const query = toMergeQuery(form);
  if (query === undefined) {
    integrity.value = undefined;
    fetchError.value = validateQuery(form);
    return;
  }
  fetchLoading.value = true;
  try {
    result.value = await getCrossSourceResult(query);
  } catch {
    result.value = undefined;
    fetchError.value = `读取跨源合并结果失败：请确认当前账号有 ${AI_CROSS_SOURCE_PERMISSIONS.query} 权限`;
  } finally {
    fetchLoading.value = false;
  }
}
</script>

<template>
  <Page auto-content-height>
    <ElCard class="mb-4" header="跨源合并结果（按执行键查询）">
      <ElAlert
        class="mb-3"
        :closable="false"
        title="先问授权完整性口径，口径为「完整」时才去取数字。口径为「未出具」时服务端不返回任何金额与来源条数，页面也不会渲染任何数字。"
        type="info"
      />
      <ElForm inline label-width="110px">
        <ElFormItem label="执行幂等键" required>
          <ElInput
            v-model="form.executionKey"
            data-testid="ai-cross-source-execution-key"
            placeholder="跨源执行的幂等键"
            style="width: 220px"
          />
        </ElFormItem>
        <ElFormItem label="应用编号" required>
          <ElInputNumber
            v-model="form.applicationId"
            :min="1"
            data-testid="ai-cross-source-application-id"
            style="width: 150px"
          />
        </ElFormItem>
        <ElFormItem label="主体类型" required>
          <ElSelect
            v-model="form.subjectType"
            data-testid="ai-cross-source-subject-type"
            style="width: 190px"
          >
            <ElOption
              v-for="item in CROSS_SOURCE_SUBJECT_TYPE_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="外部用户标识" required>
          <ElInput
            v-model="form.externalUserId"
            data-testid="ai-cross-source-external-user-id"
            placeholder="可信外部用户标识"
            style="width: 220px"
          />
        </ElFormItem>
        <ElFormItem label="调用方角色" required>
          <ElSelect
            v-model="form.callerRoles"
            collapse-tags
            collapse-tags-tooltip
            data-testid="ai-cross-source-caller-roles"
            multiple
            placeholder="至少选一个；角色决定能看到哪些字段"
            style="width: 320px"
          >
            <ElOption
              v-for="item in CROSS_SOURCE_CALLER_ROLE_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="此前已见角色">
          <ElSelect
            v-model="form.previouslySeenRoles"
            clearable
            collapse-tags
            data-testid="ai-cross-source-previously-seen-roles"
            multiple
            placeholder="可选：此前已拿到过合计覆盖的来源角色"
            style="width: 320px"
          >
            <ElOption
              v-for="item in CROSS_SOURCE_CALLER_ROLE_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem>
          <ElButton
            :loading="probeLoading"
            data-testid="ai-cross-source-probe"
            type="primary"
            @click="handleProbe"
          >
            先查口径
          </ElButton>
          <ElButton
            :disabled="!fetchAllowed"
            :loading="fetchLoading"
            data-testid="ai-cross-source-fetch"
            @click="handleFetch"
          >
            再取数字
          </ElButton>
        </ElFormItem>
      </ElForm>
      <ElAlert
        v-if="formError"
        :closable="false"
        data-testid="ai-cross-source-form-error"
        :title="formError"
        type="warning"
      />
      <ElAlert
        v-if="probeError"
        class="mt-3"
        :closable="false"
        data-testid="ai-cross-source-probe-error"
        :title="probeError"
        type="error"
      />
      <ElAlert
        v-if="fetchError"
        class="mt-3"
        :closable="false"
        data-testid="ai-cross-source-fetch-error"
        :title="fetchError"
        type="warning"
      />
    </ElCard>

    <ElCard v-if="integrity" class="mb-4" header="授权完整性口径">
      <div class="flex flex-wrap items-center gap-2">
        <span>口径</span>
        <ElTag
          data-testid="ai-cross-source-integrity-state"
          :type="integrityTone(integrity)"
        >
          {{ integrityStateCode(integrity) }} ·
          {{ integrityStateText(integrity) }}
        </ElTag>
      </div>
      <p
        v-if="integrityReasonText(integrity)"
        class="mt-2 text-sm"
        data-testid="ai-cross-source-integrity-reason"
      >
        {{ integrityReasonText(integrity) }}
      </p>
      <p v-else class="mt-2 text-sm text-muted-foreground">
        放行没有理由可编，因此该口径不携带任何说明文本。
      </p>
    </ElCard>

    <!--
      数字区闸门：口径不放行时整块不渲染。
      这里刻意用 v-if 而不是 v-show——隐藏起来的数字仍然在 DOM 里，
      等于把 fail-closed 承诺交给了一个 CSS 类。
    -->
    <ElCard
      v-if="result && amountsVisible"
      class="mb-4"
      data-testid="ai-cross-source-amounts"
      header="合并结果"
    >
      <div class="flex flex-wrap items-center gap-6 text-sm">
        <span>执行键：{{ result.executionKey ?? '未出具' }}</span>
        <span>指标码：{{ result.metricCode ?? '未出具' }}</span>
        <span>币种：{{ result.currency ?? '未出具' }}</span>
        <span>合计金额：{{ formatAmount(result.totalAmount) }}</span>
        <span>来源数：{{ result.sourceCount ?? '未出具' }}</span>
      </div>
      <div class="mt-2 flex flex-wrap items-center gap-6 text-sm">
        <span>
          口径时间点：{{ formatConsistencyAsOf(result.consistencyAsOf) }}
        </span>
        <span>最大时间偏移：{{ formatSkew(result.maxSkewMillis) }}</span>
        <span>
          技术完整性：{{
            result.complete ? '本次执行产出完整结果' : '本次执行未产出完整结果'
          }}
        </span>
      </div>
      <p class="mt-2 text-xs text-muted-foreground">
        口径时间点与时间偏移如实展示：偏移越大，各来源越不具可比性，合计口径也越需要自己判断。
      </p>
      <ElTable
        v-if="sourceList.length > 0"
        class="mt-3"
        :data="sourceList"
        data-testid="ai-cross-source-sources"
        size="small"
      >
        <ElTableColumn label="来源角色" min-width="220" prop="role" />
        <ElTableColumn label="金额" min-width="160">
          <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty
        v-else
        class="mt-3"
        description="本次口径未给出分来源明细（角色不允许查看明细，或没有可披露的来源）"
        :image-size="60"
      />
    </ElCard>

    <ElCard
      v-else-if="result"
      class="mb-4"
      data-testid="ai-cross-source-withheld"
      header="合并结果"
    >
      <ElAlert
        :closable="false"
        data-testid="ai-cross-source-withheld-notice"
        :title="withheldNotice(result.integrity)"
        type="error"
      />
    </ElCard>
  </Page>
</template>
