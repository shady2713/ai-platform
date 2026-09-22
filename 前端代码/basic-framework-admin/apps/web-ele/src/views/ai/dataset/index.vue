<script lang="ts" setup>
/**
 * AI 数据集页面（D10）：来源声明、语义版本、验证与发布。
 *
 * 版本命令独立授权（创建/验证/发布各有权限码），发布失败一定带原因（漂移/未验证）。
 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiDatasetApi } from '#/api/ai/data';

import { Page, useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteDataset,
  getDatasetPage,
  updateDatasetStatus,
} from '#/api/ai/data';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import {
  AI_DATASET_PERMISSIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
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

function handleEdit(row: AiDatasetApi.Dataset) {
  formModalApi.setData(row).open();
}

function handleVersions(row: AiDatasetApi.Dataset) {
  versionsModalApi.setData(row).open();
}

async function handleToggle(row: AiDatasetApi.Dataset) {
  const enabled = row.status !== 'ENABLED';
  await updateDatasetStatus(row.id, row.version, enabled);
  showSuccessMessage(enabled ? '已启用' : '已停用');
  handleRefresh();
}

async function handleDelete(row: AiDatasetApi.Dataset) {
  await deleteDataset(row.id, row.version);
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
          await getDatasetPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiDatasetApi.Dataset>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <VersionsModal @success="handleRefresh" />
    <Grid table-title="数据集列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.create', ['数据集']),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: [AI_DATASET_PERMISSIONS.create],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '语义版本',
              type: 'primary',
              link: true,
              auth: [AI_DATASET_PERMISSIONS.query],
              onClick: handleVersions.bind(null, row),
            },
            {
              label: row.status === 'ENABLED' ? '停用' : '启用',
              type: 'primary',
              link: true,
              auth: [AI_DATASET_PERMISSIONS.update],
              onClick: handleToggle.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'primary',
              link: true,
              icon: ACTION_ICON.EDIT,
              auth: [AI_DATASET_PERMISSIONS.update],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'danger',
              link: true,
              icon: ACTION_ICON.DELETE,
              auth: [AI_DATASET_PERMISSIONS.delete],
              popConfirm: {
                title: `被报表引用的版本不可删除数据集；确认删除 ${row.name}？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
