<script lang="ts" setup>
/**
 * AI 工具页面（D10）：注册、启停、版本与执行政策。
 *
 * 政策与版本是显式命令：界面不提供"临时放开一次"之类的旁路；
 * 版本发布失败（写工具、来源未发布）会原样展示后端原因。
 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiToolApi } from '#/api/ai/data';

import { Page, useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { deleteTool, getToolPage, updateToolStatus } from '#/api/ai/data';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { AI_TOOL_PERMISSIONS, useGridColumns, useGridFormSchema } from './data';
import Form from './modules/form.vue';
import Versions from './modules/versions.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [VersionsModal, versionsModalApi] = useVbenModal({
  connectedComponent: Versions,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData({}).open();
}

function handleEdit(row: AiToolApi.Tool) {
  formModalApi.setData(row).open();
}

function handleVersions(row: AiToolApi.Tool) {
  versionsModalApi.setData(row).open();
}

async function handleToggle(row: AiToolApi.Tool) {
  const enabled = row.status !== 'ENABLED';
  await updateToolStatus(row.id, row.version, enabled);
  showSuccessMessage(enabled ? '已启用' : '已停用');
  handleRefresh();
}

async function handleDelete(row: AiToolApi.Tool) {
  await deleteTool(row.id, row.version);
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
          await getToolPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiToolApi.Tool>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <VersionsModal @success="handleRefresh" />
    <Grid table-title="工具列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.create', ['工具']),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: [AI_TOOL_PERMISSIONS.create],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '版本与政策',
              type: 'primary',
              link: true,
              auth: [AI_TOOL_PERMISSIONS.version],
              onClick: handleVersions.bind(null, row),
            },
            {
              label: row.status === 'ENABLED' ? '停用' : '启用',
              type: 'primary',
              link: true,
              auth: [AI_TOOL_PERMISSIONS.update],
              onClick: handleToggle.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'primary',
              link: true,
              icon: ACTION_ICON.EDIT,
              auth: [AI_TOOL_PERMISSIONS.update],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'danger',
              link: true,
              icon: ACTION_ICON.DELETE,
              auth: [AI_TOOL_PERMISSIONS.delete],
              popConfirm: {
                title: `被服务/步骤引用的工具不可删除；确认删除 ${row.name}？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
