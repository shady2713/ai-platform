<script lang="ts" setup>
import type { PanelFeedback } from './data';

/**
 * AI 评测控制面页面（Q05，菜单 4120 对应的页面；数据来自 Q04 的 `/ai/eval/**`）。
 *
 * 本文件是**薄壳**：应用/套件选择、套件增删改冻结、权限判定、以及面板之间的编排；
 * 功能区在 `modules/` 下各自成组件（样例、运行比较、失败分类、人工复核、可复现报告），
 * 纯口径在 `data.ts`（文案与最小校验）与 `analysis.ts`（解析、聚合、比较、报告）里。
 *
 * 权限码与接口一致：读取 `ai:eval:query`、套件/样例维护 `ai:eval:manage`、执行 `ai:eval:run`、
 * 复核 `ai:eval:review`；无权限时隐藏对应按钮并说明，服务端仍会拒绝。
 * 面板失败/成功消息统一抛到本页顶部展示（`feedback` / `clearFeedback`），避免多处告警。
 */
import type { AiEvalApi } from '#/api/ai/evaluation';

import { computed, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { getApplicationPage } from '#/api/ai/application';
import {
  createSuite,
  freezeSuite,
  getRun,
  getSuitePage,
  newSuiteRevision,
  updateSuite,
} from '#/api/ai/evaluation';

import { buildSuiteQuery } from './analysis';
import {
  AI_EVAL_PERMISSIONS,
  DATA_LEVEL_OPTIONS,
  DEFAULT_SUITE_FORM,
  describeDataLevel,
  describeSubjectType,
  describeSuiteStatus,
  digestText,
  isSuiteEditable,
  parsePositiveInt,
  SUBJECT_TYPE_OPTIONS,
  SUITE_STATUS_OPTIONS,
  trimmedOrUndefined,
} from './data';
import FailurePanel from './modules/FailurePanel.vue';
import ReportPanel from './modules/ReportPanel.vue';
import ReviewPanel from './modules/ReviewPanel.vue';
import RunComparePanel from './modules/RunComparePanel.vue';
import SamplePanel from './modules/SamplePanel.vue';

const { hasAccessByCodes } = useAccess();

const canQuery = computed(() => hasAccessByCodes([AI_EVAL_PERMISSIONS.query]));
const canManage = computed(() =>
  hasAccessByCodes([AI_EVAL_PERMISSIONS.manage]),
);
const canRun = computed(() => hasAccessByCodes([AI_EVAL_PERMISSIONS.run]));
const canReview = computed(() =>
  hasAccessByCodes([AI_EVAL_PERMISSIONS.review]),
);

const applications = ref<Array<{ appCode: string; id: number; name: string }>>(
  [],
);
const applicationId = ref<number>();
const suiteStatus = ref<string>();
const loading = ref(false);
const error = ref('');
const notice = ref('');

const suites = ref<AiEvalApi.Suite[]>([]);
const suitePageNo = ref(1);
const suitePageSize = ref(20);
const suiteTotal = ref(0);
const suiteOpen = ref(false);
const suiteEditing = ref(false);
const suiteEditId = ref<number>();
const suiteEditVersion = ref<number>();
const suiteSubmitting = ref(false);
const suiteForm = ref({ ...DEFAULT_SUITE_FORM });

const selectedSuiteId = ref<number>();
const selectedSuite = computed(() =>
  suites.value.find((item) => item.id === selectedSuiteId.value),
);

/** 选定运行的详情（父页面读一次，结果/复核/报告面板共用，避免各自重复请求） */
const activeRun = ref<AiEvalApi.Run>();
/** 面板刷新信号：套件/运行/复核等改变后自增，子面板只监听它，不再互相调用 */
const refreshKey = ref(0);
/** 上下文清空信号：重新选套件/切换应用时自增，运行面板据此清掉比较选择 */
const contextKey = ref(0);

async function loadApplications(): Promise<void> {
  const page = await getApplicationPage({ pageNo: 1, pageSize: 100 });
  applications.value = page.list.map((item) => ({
    appCode: item.appCode,
    id: item.id,
    name: item.name,
  }));
  applicationId.value ??= applications.value[0]?.id;
}

function handleFeedback(payload: PanelFeedback): void {
  if (payload.kind === 'error') {
    error.value = payload.message;
    notice.value = '';
    return;
  }
  notice.value = payload.message;
  error.value = '';
}

function clearFeedback(): void {
  error.value = '';
  notice.value = '';
}

async function refreshSuites(): Promise<void> {
  if (applicationId.value === undefined) {
    suites.value = [];
    suiteTotal.value = 0;
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const page = await getSuitePage(
      buildSuiteQuery({
        applicationId: applicationId.value,
        pageNo: suitePageNo.value,
        pageSize: suitePageSize.value,
        status: suiteStatus.value,
      }),
    );
    suites.value = page.list;
    suiteTotal.value = page.total;
  } catch {
    error.value = `读取评测套件失败：请确认应用存在且当前账号有 ${AI_EVAL_PERMISSIONS.query} 权限`;
    suites.value = [];
    suiteTotal.value = 0;
  } finally {
    loading.value = false;
  }
}

/**
 * 套件列表整体刷新：选中项尽量保留（不在本页时退回本页第一个套件），
 * 然后让样例/运行/结果/复核/报告面板按新套件重新读取。
 */
async function reloadSuites(): Promise<void> {
  await refreshSuites();
  const current = suites.value.find(
    (item) => item.id === selectedSuiteId.value,
  );
  const next = current ?? suites.value[0];
  if (next === undefined) {
    selectedSuiteId.value = undefined;
    activeRun.value = undefined;
    refreshKey.value += 1;
    return;
  }
  if (next.id !== selectedSuiteId.value) {
    // 换了套件：上一个套件的运行选择必须清掉
    selectedSuiteId.value = next.id;
    activeRun.value = undefined;
  }
  refreshKey.value += 1;
}

/** 选中套件（表格行按钮）：清掉运行上下文，再让面板重新读取 */
function selectSuite(suite: AiEvalApi.Suite): void {
  clearFeedback();
  selectedSuiteId.value = suite.id;
  activeRun.value = undefined;
  contextKey.value += 1;
  refreshKey.value += 1;
}

/** 样例面板改动后只刷新套件行（冻结样例数），不动选中项与运行上下文 */
async function handleSamplesChanged(): Promise<void> {
  await refreshSuites();
}

/** 复核会改写判定与计数：重读运行详情，再刷新各面板 */
async function handleReviewed(): Promise<void> {
  const run = activeRun.value;
  if (run === undefined) {
    return;
  }
  try {
    activeRun.value = await getRun(run.id);
  } catch {
    error.value = `读取评测运行失败：请确认当前账号有 ${AI_EVAL_PERMISSIONS.query} 权限`;
  }
  refreshKey.value += 1;
}

/** 选定运行（运行面板按钮）：读运行详情给结果/复核/报告面板用 */
async function handleSelectRun(runId: number): Promise<void> {
  try {
    activeRun.value = await getRun(runId);
    refreshKey.value += 1;
  } catch {
    error.value = `读取评测运行失败：请确认当前账号有 ${AI_EVAL_PERMISSIONS.query} 权限`;
  }
}

async function handleApplicationChange(): Promise<void> {
  suitePageNo.value = 1;
  selectedSuiteId.value = undefined;
  activeRun.value = undefined;
  contextKey.value += 1;
  await reloadSuites();
}

async function handleSuiteSearch(): Promise<void> {
  suitePageNo.value = 1;
  await reloadSuites();
}

async function handleSuitePageChange(next: number): Promise<void> {
  suitePageNo.value = next;
  await reloadSuites();
}

function openSuiteDialog(suite?: AiEvalApi.Suite): void {
  clearFeedback();
  suiteEditId.value = suite?.id;
  suiteEditVersion.value = suite?.version;
  if (suite === undefined) {
    suiteEditing.value = false;
    suiteForm.value = { ...DEFAULT_SUITE_FORM };
  } else {
    suiteEditing.value = true;
    suiteForm.value = {
      code: suite.code,
      dataLevel: suite.dataLevel ?? 'L2_INTERNAL',
      description: suite.description ?? '',
      externalUserId: suite.externalUserId ?? '',
      name: suite.name,
      serviceId: String(suite.serviceId),
      subjectType: suite.subjectType,
    };
  }
  suiteOpen.value = true;
}

async function submitSuite(): Promise<void> {
  const form = suiteForm.value;
  const name = form.name.trim();
  if (name === '') {
    error.value = '套件名称不能为空';
    return;
  }
  error.value = '';
  notice.value = '';
  suiteSubmitting.value = true;
  try {
    const editId = suiteEditId.value;
    const editVersion = suiteEditVersion.value;
    if (
      suiteEditing.value &&
      editId !== undefined &&
      editVersion !== undefined
    ) {
      await updateSuite({
        dataLevel: form.dataLevel,
        description: trimmedOrUndefined(form.description),
        id: editId,
        name,
        version: editVersion,
      });
      notice.value = `套件 ${form.code} 已更新（仅名称/说明/分级；标识与服务不可修改）`;
    } else {
      if (applicationId.value === undefined) {
        error.value = '请先选择应用';
        return;
      }
      if (form.code.trim() === '') {
        error.value = '套件标识不能为空（应用内唯一，创建后不可修改）';
        return;
      }
      const serviceId = parsePositiveInt(form.serviceId);
      if (serviceId === undefined) {
        error.value = '服务编号必须是正整数（被评测的服务）';
        return;
      }
      await createSuite({
        applicationId: applicationId.value,
        code: form.code.trim(),
        dataLevel: form.dataLevel,
        description: trimmedOrUndefined(form.description),
        externalUserId: trimmedOrUndefined(form.externalUserId),
        name,
        serviceId,
        subjectType: form.subjectType,
      });
      notice.value = `套件 ${form.code.trim()} 已创建（草稿）：先加样例，再冻结并执行评测`;
    }
    suiteOpen.value = false;
    await reloadSuites();
  } catch {
    error.value = `保存套件失败：标识在同一应用内唯一，且需要 ${AI_EVAL_PERMISSIONS.manage} 权限`;
  } finally {
    suiteSubmitting.value = false;
  }
}

async function freeze(row: AiEvalApi.Suite): Promise<void> {
  clearFeedback();
  try {
    await freezeSuite({ id: row.id, version: row.version });
    notice.value = `套件 ${row.code} 已冻结（修订 ${row.revision}）：套件与样例转为只读，调整请创建新修订`;
    await reloadSuites();
  } catch {
    error.value = `冻结失败：套件必须至少有一条合规样例，且需要 ${AI_EVAL_PERMISSIONS.manage} 权限`;
  }
}

async function createNewRevision(row: AiEvalApi.Suite): Promise<void> {
  clearFeedback();
  try {
    await newSuiteRevision({ id: row.id, version: row.version });
    notice.value = `套件 ${row.code} 已创建新修订（回到草稿；历史运行的摘要保持原值）`;
    await reloadSuites();
  } catch {
    error.value = `创建新修订失败：套件必须处于已冻结状态，且需要 ${AI_EVAL_PERMISSIONS.manage} 权限`;
  }
}

onMounted(async () => {
  try {
    await loadApplications();
  } catch {
    error.value =
      '读取应用列表失败：请确认当前账号有 ai:application:query 权限';
    return;
  }
  await reloadSuites();
});
</script>

<template>
  <Page auto-content-height>
    <ElCard class="mb-4">
      <div class="flex flex-wrap items-center gap-3">
        <span>应用</span>
        <ElSelect
          v-model="applicationId"
          data-testid="ai-eval-application"
          placeholder="选择应用"
          style="width: 220px"
          @change="handleApplicationChange"
        >
          <ElOption
            v-for="item in applications"
            :key="item.id"
            :label="`${item.name}（${item.appCode}）`"
            :value="item.id"
          />
        </ElSelect>
        <span>套件状态</span>
        <ElSelect
          v-model="suiteStatus"
          clearable
          data-testid="ai-eval-suite-status"
          placeholder="全部状态"
          style="width: 180px"
          @change="handleSuiteSearch"
        >
          <ElOption
            v-for="item in SUITE_STATUS_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </ElSelect>
        <ElButton
          data-testid="ai-eval-refresh"
          :loading="loading"
          @click="handleSuiteSearch"
        >
          刷新
        </ElButton>
        <ElButton
          v-if="canManage"
          data-testid="ai-eval-suite-create"
          type="primary"
          @click="openSuiteDialog()"
        >
          新建套件
        </ElButton>
      </div>
      <ElAlert
        v-if="error"
        class="mt-3"
        data-testid="ai-eval-error"
        :closable="false"
        :title="error"
        type="error"
      />
      <ElAlert
        v-if="notice"
        class="mt-3"
        data-testid="ai-eval-notice"
        :closable="false"
        :title="notice"
        type="success"
      />
      <p v-if="!canQuery" class="mt-3 text-gray-500">
        当前账号没有
        {{ AI_EVAL_PERMISSIONS.query }} 权限：套件、样例、运行与报告都不会返回。
      </p>
      <p
        v-if="!canManage"
        class="mt-3 text-gray-500"
        data-testid="ai-eval-manage-hint"
      >
        当前账号没有
        {{ AI_EVAL_PERMISSIONS.manage }}
        权限：可以查看套件与样例，但不能创建、编辑、冻结、删除。
      </p>
    </ElCard>

    <ElCard class="mb-4" header="评测套件（冻结后只读：编辑前先创建新修订）">
      <ElEmpty v-if="suites.length === 0" description="该应用下没有评测套件" />
      <ElTable
        v-else
        data-testid="ai-eval-suite-table"
        :data="suites"
        size="small"
      >
        <ElTableColumn label="套件" min-width="200">
          <template #default="{ row }">
            {{ row.code }} · {{ row.name }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" min-width="190">
          <template #default="{ row }">
            <ElTag
              size="small"
              :type="isSuiteEditable(row.status) ? 'success' : 'info'"
            >
              {{ describeSuiteStatus(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="修订" prop="revision" width="70" />
        <ElTableColumn label="冻结样例数" width="110">
          <template #default="{ row }">
            {{ row.caseCount ?? '未冻结' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="服务 / 主体" min-width="170">
          <template #default="{ row }">
            {{ row.serviceId }} / {{ describeSubjectType(row.subjectType) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="分级" min-width="150">
          <template #default="{ row }">
            {{ describeDataLevel(row.dataLevel) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="冻结内容摘要" min-width="140">
          <template #default="{ row }">
            {{ digestText(row.contentDigest) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="行版本" prop="version" width="80" />
        <ElTableColumn fixed="right" label="操作" width="250">
          <template #default="{ row }">
            <ElButton link type="primary" @click="selectSuite(row)">
              样例与运行
            </ElButton>
            <ElButton
              v-if="canManage && isSuiteEditable(row.status)"
              link
              type="primary"
              @click="openSuiteDialog(row)"
            >
              编辑
            </ElButton>
            <ElButton
              v-if="canManage && isSuiteEditable(row.status)"
              link
              type="warning"
              @click="freeze(row)"
            >
              冻结
            </ElButton>
            <ElButton
              v-if="canManage && !isSuiteEditable(row.status)"
              link
              type="success"
              @click="createNewRevision(row)"
            >
              新建修订
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElPagination
        class="mt-3 justify-end"
        data-testid="ai-eval-suite-pagination"
        :current-page="suitePageNo"
        :page-size="suitePageSize"
        :total="suiteTotal"
        @current-change="handleSuitePageChange"
      />
    </ElCard>

    <SamplePanel
      :can-manage="canManage"
      :refresh-key="refreshKey"
      :suite="selectedSuite"
      @changed="handleSamplesChanged"
      @clear-feedback="clearFeedback"
      @feedback="handleFeedback"
    />

    <RunComparePanel
      :can-run="canRun"
      :context-key="contextKey"
      :refresh-key="refreshKey"
      :suite="selectedSuite"
      @clear-feedback="clearFeedback"
      @feedback="handleFeedback"
      @select-run="handleSelectRun"
    />

    <FailurePanel
      :refresh-key="refreshKey"
      :run="activeRun"
      @feedback="handleFeedback"
    />

    <ReviewPanel
      :can-review="canReview"
      :refresh-key="refreshKey"
      :run="activeRun"
      @clear-feedback="clearFeedback"
      @feedback="handleFeedback"
      @reviewed="handleReviewed"
    />

    <ReportPanel
      :run="activeRun"
      @clear-feedback="clearFeedback"
      @feedback="handleFeedback"
    />

    <ElDialog
      v-model="suiteOpen"
      :title="suiteEditing ? '编辑套件（仅草稿）' : '新建套件（草稿）'"
      width="560px"
    >
      <ElForm label-width="110px">
        <ElFormItem v-if="!suiteEditing" label="套件标识">
          <ElInput
            v-model="suiteForm.code"
            data-testid="ai-eval-suite-code"
            placeholder="应用内唯一，创建后不可修改"
          />
        </ElFormItem>
        <ElFormItem label="名称">
          <ElInput v-model="suiteForm.name" data-testid="ai-eval-suite-name" />
        </ElFormItem>
        <ElFormItem label="说明">
          <ElInput
            v-model="suiteForm.description"
            data-testid="ai-eval-suite-description"
            type="textarea"
          />
        </ElFormItem>
        <ElFormItem v-if="!suiteEditing" label="服务编号">
          <ElInput
            v-model="suiteForm.serviceId"
            data-testid="ai-eval-suite-service"
            placeholder="被评测的服务编号（正整数）"
          />
        </ElFormItem>
        <ElFormItem v-if="!suiteEditing" label="执行主体">
          <ElSelect
            v-model="suiteForm.subjectType"
            data-testid="ai-eval-suite-subject"
            style="width: 100%"
          >
            <ElOption
              v-for="item in SUBJECT_TYPE_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="!suiteEditing" label="主体标识">
          <ElInput
            v-model="suiteForm.externalUserId"
            data-testid="ai-eval-suite-external-user"
            placeholder="合成主体，不是真实用户"
          />
        </ElFormItem>
        <ElFormItem label="样例分级">
          <ElSelect
            v-model="suiteForm.dataLevel"
            data-testid="ai-eval-suite-data-level"
            style="width: 100%"
          >
            <ElOption
              v-for="item in DATA_LEVEL_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="suiteOpen = false">取消</ElButton>
        <ElButton
          data-testid="ai-eval-suite-submit"
          :loading="suiteSubmitting"
          type="primary"
          @click="submitSuite"
        >
          {{ suiteEditing ? '保存套件' : '创建套件' }}
        </ElButton>
      </template>
    </ElDialog>
  </Page>
</template>
