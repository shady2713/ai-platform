<script lang="ts" setup>
/**
 * AI 流程编排页面（X08）：定义列表 + 版本管理 + 受控运行留痕。
 *
 * 拆成主列表加两个子面板，而不是把三块塞进一个页面：版本与运行都以流程为作用域，
 * 天然是"选中一行 → 看它的版本/运行"，平铺会让主列表被三个分页互相淹没。
 * 受理运行单独用 ai:workflow:run 授权的入口打开，只读查看用 ai:workflow:query，
 * 这样没有运行权限的人不会看到可点的受理表单。
 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiWorkflowApi } from '#/api/ai/workflow';

import { Page, useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteWorkflow,
  getWorkflowPage,
  updateWorkflowStatus,
} from '#/api/ai/workflow';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import {
  AI_WORKFLOW_PERMISSIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Form from './modules/form.vue';
import Runs from './modules/runs.vue';
import Versions from './modules/versions.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [VersionsModal, versionsModalApi] = useVbenModal({
  connectedComponent: Versions,
  destroyOnClose: true,
});
const [RunsModal, runsModalApi] = useVbenModal({
  connectedComponent: Runs,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData({}).open();
}

function handleEdit(row: AiWorkflowApi.Workflow) {
  formModalApi.setData(row).open();
}

function handleVersions(row: AiWorkflowApi.Workflow) {
  versionsModalApi.setData(row).open();
}

/** 只读查看运行记录：query 权限即可 */
function handleRuns(row: AiWorkflowApi.Workflow) {
  runsModalApi.setData({ accept: false, workflow: row }).open();
}

/** 受理运行需要单独的 ai:workflow:run 权限 */
function handleAccept(row: AiWorkflowApi.Workflow) {
  runsModalApi.setData({ accept: true, workflow: row }).open();
}

async function handleToggle(row: AiWorkflowApi.Workflow) {
  const enabled = row.status !== 'ENABLED';
  await updateWorkflowStatus({
    enabled,
    id: row.id,
    version: row.version,
  });
  showSuccessMessage(enabled ? '已启用' : '已停用');
  handleRefresh();
}

async function handleDelete(row: AiWorkflowApi.Workflow) {
  await deleteWorkflow(row.id, row.version);
  showSuccessMessage($t('ui.actionMessage.deleteSuccess', [row.name]));
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: { schema: useGridFormSchema() },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) =>
          await getWorkflowPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiWorkflowApi.Workflow>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <VersionsModal @success="handleRefresh" />
    <RunsModal @success="handleRefresh" />
    <Grid table-title="流程定义列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.create', ['流程']),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: [AI_WORKFLOW_PERMISSIONS.manage],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '版本管理',
              type: 'primary',
              link: true,
              auth: [AI_WORKFLOW_PERMISSIONS.query],
              onClick: handleVersions.bind(null, row),
            },
            {
              label: '受理运行',
              type: 'primary',
              link: true,
              auth: [AI_WORKFLOW_PERMISSIONS.run],
              onClick: handleAccept.bind(null, row),
            },
            {
              label: '运行记录',
              type: 'primary',
              link: true,
              auth: [AI_WORKFLOW_PERMISSIONS.query],
              onClick: handleRuns.bind(null, row),
            },
            {
              label: row.status === 'ENABLED' ? '停用' : '启用',
              type: 'primary',
              link: true,
              auth: [AI_WORKFLOW_PERMISSIONS.manage],
              onClick: handleToggle.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'primary',
              link: true,
              icon: ACTION_ICON.EDIT,
              auth: [AI_WORKFLOW_PERMISSIONS.manage],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'danger',
              link: true,
              icon: ACTION_ICON.DELETE,
              auth: [AI_WORKFLOW_PERMISSIONS.delete],
              popConfirm: {
                title: `删除会一并影响该流程的版本与运行留痕；确认删除 ${row.name}？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
