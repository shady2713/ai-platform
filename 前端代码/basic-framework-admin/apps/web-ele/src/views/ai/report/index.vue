<script lang="ts" setup>
/**
 * 个人报表预览页（R07）。
 *
 * 通道与 O08 的在线调试一致：用应用客户端凭据换取**短期受限票据**（只驻留内存），
 * 再用票据读取/刷新/修订该主体自己的报表——报表归属由服务端会话决定，页面不提供归属与行范围字段。
 *
 * 页面只提供真实存在的能力：读取版本、刷新（可刷新报表）、对话修改；**不提供**分享/发布按钮
 * （首期私人报表，分享发布单独迭代，未实现的能力不放按钮）。
 */
import type { AiReportApi } from '#/api/ai/report';

import { computed, onBeforeUnmount, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { issueDebugTicket } from '#/api/ai/open-platform';
import {
  getReportPage,
  listReportVersions,
  readCurrentReportVersion,
  readRefreshState,
  readReportVersion,
  refreshReport,
  reviseReport,
} from '#/api/ai/report';
import { showErrorMessage, showSuccessMessage } from '#/utils/feedback';

import {
  AI_REPORT_PERMISSIONS,
  canRefresh,
  previewFrom,
  refreshResultText,
  REPORT_MODE_TEXT,
  revisionResultText,
  versionOptions,
} from './data';
// 报表渲染组件来自共享包（本卡不允许改依赖声明，故按源码相对路径引入）
import ReportPreview from './ReportPreview.vue';

/** 页面权限码（菜单可见性用；接口鉴权走应用端票据） */
const permissionCode = AI_REPORT_PERMISSIONS.preview;

const appCode = ref('');
const appSecret = ref('');
const subjectType = ref('USER');
const externalUserId = ref('');
const ticket = ref('');
const loading = ref(false);
const failureMessage = ref('');

const reports = ref<AiReportApi.Report[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(10);
const selected = ref<AiReportApi.Report>();
const version = ref<AiReportApi.Version>();
const versions = ref<AiReportApi.VersionBrief[]>([]);
const selectedVersionNo = ref<number>();
const refreshState = ref<AiReportApi.RefreshState>();
const refreshMessage = ref('');

const reviseVisible = ref(false);
const reviseInstruction = ref('');
const reviseEndpointId = ref<number>();
const reviseMessage = ref('');

const preview = computed(() => previewFrom(version.value, refreshState.value));
/** 卡片标题：名称 + 模式语义（模式是报表级语义，界面只做可读映射） */
const selectedHeaderText = computed(() => {
  const report = selected.value;
  if (!report) {
    return '';
  }
  return `${report.name}（${REPORT_MODE_TEXT[report.mode] ?? report.mode}）`;
});
const versionSelectOptions = computed(() => versionOptions(versions.value));
const refreshable = computed(() => canRefresh(selected.value));

function requireTicket(): string {
  if (!ticket.value) {
    throw new Error('请先换取访问票据');
  }
  return ticket.value;
}

/** 换取票据：凭据只在本次请求里使用，票据只驻留内存（不写任何存储）。 */
async function issueTicket() {
  loading.value = true;
  failureMessage.value = '';
  try {
    const issued = await issueDebugTicket(
      appCode.value,
      appSecret.value,
      subjectType.value,
      externalUserId.value || undefined,
    );
    ticket.value = issued.token;
    showSuccessMessage('已换取访问票据（仅本次会话内存保留）');
    await loadReports();
  } catch (error) {
    failureMessage.value =
      error instanceof Error ? error.message : '换取票据失败';
  } finally {
    loading.value = false;
  }
}

async function loadReports() {
  const page = await getReportPage(requireTicket(), {
    pageNo: pageNo.value,
    pageSize: pageSize.value,
  });
  reports.value = page.list;
  total.value = page.total;
}

async function openReport(report: AiReportApi.Report) {
  selected.value = report;
  refreshMessage.value = '';
  selectedVersionNo.value = report.publishedVersionNo;
  await reloadReport(report);
}

/** 重新读取当前版本/版本列表/刷新状态（不清空操作提示：刷新结果要留在页面上）。 */
async function reloadReport(report: AiReportApi.Report) {
  version.value = await readCurrentReportVersion(requireTicket(), report.id);
  versions.value = await listReportVersions(requireTicket(), report.id);
  refreshState.value = await readRefreshState(requireTicket(), report.id).catch(
    () => undefined,
  );
}

async function switchVersion(versionNo: number | undefined) {
  if (!selected.value || !versionNo) {
    return;
  }
  version.value = await readReportVersion(
    requireTicket(),
    selected.value.id,
    versionNo,
  );
}

async function refresh() {
  if (!selected.value) {
    return;
  }
  try {
    const result = await refreshReport(requireTicket(), {
      id: selected.value.id,
    });
    // 先重读（刷新成功会切换生效版本），再展示本次结果：失败原因与时间要留在页面上
    await reloadReport(selected.value);
    refreshMessage.value = refreshResultText(result);
  } catch (error) {
    // 失权（409）等前置条件失败：如实提示，不假装刷新成功
    refreshMessage.value = error instanceof Error ? error.message : '刷新失败';
  }
}

async function submitRevision() {
  if (!selected.value || !reviseEndpointId.value || !reviseInstruction.value) {
    showErrorMessage('请填写修改指令与模型端点编号');
    return;
  }
  try {
    const result = await reviseReport(requireTicket(), {
      baseVersionNo: selected.value.publishedVersionNo,
      endpointId: reviseEndpointId.value,
      id: selected.value.id,
      instruction: reviseInstruction.value,
      version: selected.value.version,
    });
    reviseMessage.value = revisionResultText(result);
    reviseVisible.value = false;
    reviseInstruction.value = '';
    await openReport(selected.value);
  } catch (error) {
    reviseMessage.value =
      error instanceof Error ? error.message : '对话修改失败';
  }
}

// 票据与预览数据只驻留内存：离开页面即清空（不写入 storage）
onBeforeUnmount(() => {
  ticket.value = '';
  version.value = undefined;
  refreshState.value = undefined;
  reports.value = [];
});
</script>

<template>
  <Page
    :description="`报表预览与刷新（权限码 ${permissionCode}；接口鉴权走应用端票据）`"
    title="个人报表"
  >
    <ElCard class="mb-4" shadow="never">
      <ElForm inline label-width="100px">
        <ElFormItem label="应用标识">
          <ElInput
            v-model="appCode"
            data-testid="report-app-code"
            placeholder="appCode"
          />
        </ElFormItem>
        <ElFormItem label="客户端密钥">
          <ElInput
            v-model="appSecret"
            data-testid="report-app-secret"
            placeholder="appSecret（只在本次请求使用）"
            show-password
            type="password"
          />
        </ElFormItem>
        <ElFormItem label="主体类型">
          <ElSelect
            v-model="subjectType"
            data-testid="report-subject-type"
            style="width: 120px"
          >
            <ElOption label="USER（外部用户）" value="USER" />
            <ElOption label="APP（应用主体）" value="APP" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="外部用户标识">
          <ElInput
            v-model="externalUserId"
            data-testid="report-external-user"
            placeholder="APP 主体可留空"
          />
        </ElFormItem>
        <ElFormItem>
          <ElButton
            :loading="loading"
            data-testid="report-issue-ticket"
            type="primary"
            @click="issueTicket"
          >
            换取票据并加载
          </ElButton>
        </ElFormItem>
      </ElForm>
      <ElAlert
        v-if="failureMessage"
        :closable="false"
        :title="failureMessage"
        data-testid="report-ticket-failure"
        type="error"
      />
    </ElCard>

    <ElCard class="mb-4" shadow="never" title="我的报表">
      <ElTable
        :data="reports"
        data-testid="report-table"
        @row-click="openReport"
      >
        <ElTableColumn label="名称" prop="name" />
        <ElTableColumn label="标识" prop="code" />
        <ElTableColumn label="模式" width="220">
          <template #default="{ row }">
            {{ REPORT_MODE_TEXT[row.mode] ?? row.mode }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="当前版本" prop="publishedVersionNo" width="100" />
        <ElTableColumn label="操作" width="140">
          <template #default="{ row }">
            <ElButton
              data-testid="report-open"
              link
              type="primary"
              @click="openReport(row)"
            >
              预览
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElPagination
        v-model:current-page="pageNo"
        v-model:page-size="pageSize"
        :total="total"
        class="mt-3"
        layout="prev, pager, next"
        @current-change="loadReports"
      />
    </ElCard>

    <ElCard v-if="selected" shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>{{ selectedHeaderText }}</span>
          <div class="flex items-center gap-2">
            <ElSelect
              v-model="selectedVersionNo"
              data-testid="report-version-select"
              placeholder="切换版本"
              style="width: 180px"
              @change="switchVersion"
            >
              <ElOption
                v-for="option in versionSelectOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </ElSelect>
            <ElButton
              v-if="refreshable"
              data-testid="report-refresh"
              type="primary"
              @click="refresh"
            >
              刷新
            </ElButton>
            <ElButton
              data-testid="report-revise-open"
              @click="reviseVisible = true"
            >
              对话修改
            </ElButton>
          </div>
        </div>
      </template>

      <ElAlert
        v-if="refreshMessage"
        :closable="false"
        :title="refreshMessage"
        class="mb-2"
        data-testid="report-refresh-message"
        type="info"
      />
      <ElAlert
        v-if="reviseMessage"
        :closable="false"
        :title="reviseMessage"
        class="mb-2"
        data-testid="report-revise-message"
        type="info"
      />

      <div class="mb-2 flex items-center gap-2 text-sm">
        <ElTag data-testid="report-as-of" size="small" type="info">
          数据截至：{{ preview.asOfText || '未知' }}
        </ElTag>
        <ElTag data-testid="report-mode" size="small">
          {{ REPORT_MODE_TEXT[selected.mode] ?? selected.mode }}
        </ElTag>
      </div>

      <ReportPreview
        :data="preview.data"
        :failure-reason="preview.failureReason"
        :spec="preview.spec ?? ''"
        theme="light"
      />
    </ElCard>

    <ElDialog
      v-model="reviseVisible"
      data-testid="report-revise-dialog"
      title="对话修改报表"
    >
      <ElForm label-width="120px">
        <ElFormItem label="修改指令">
          <div data-testid="report-revise-instruction" style="width: 100%">
            <ElInput
              v-model="reviseInstruction"
              placeholder="例如：把柱状图换成折线图 / 按周汇总"
            />
          </div>
        </ElFormItem>
        <ElFormItem label="模型端点编号">
          <div data-testid="report-revise-endpoint">
            <ElInputNumber v-model="reviseEndpointId" :min="1" />
          </div>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton
          data-testid="report-revise-submit"
          type="primary"
          @click="submitRevision"
        >
          提交修改
        </ElButton>
      </template>
    </ElDialog>
  </Page>
</template>
