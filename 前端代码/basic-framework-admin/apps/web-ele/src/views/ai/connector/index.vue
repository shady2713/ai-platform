<script lang="ts" setup>
/**
 * AI 连接器页面（D10）：连接测试、接口草稿与发布、声明式配置维护。
 *
 * 权限：探测按钮受 `ai:connector:probe` 控制、接口管理受 `ai:connector:import` 控制，
 * 无权用户看不到入口（后端仍二次校验）。界面不提供任意 SQL/脚本执行入口。
 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiConnectorApi } from '#/api/ai/data';

import { ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteConnector,
  getConnectorPage,
  probeConnector,
  updateConnectorStatus,
} from '#/api/ai/data';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import {
  AI_CONNECTOR_PERMISSIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Form from './modules/form.vue';
import Operations from './modules/operations.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [OperationsModal, operationsModalApi] = useVbenModal({
  connectedComponent: Operations,
  destroyOnClose: true,
});

/** 连接测试结论（稳定原因码原样展示，不翻译成"成功/失败"这种不可诊断的说法） */
const probeHint = ref('');

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData({}).open();
}

function handleEdit(row: AiConnectorApi.Connector) {
  formModalApi.setData(row).open();
}

function handleOperations(row: AiConnectorApi.Connector) {
  operationsModalApi.setData(row).open();
}

async function handleProbe(row: AiConnectorApi.Connector) {
  const result = await probeConnector(row.id);
  probeHint.value =
    result.status === 'SUPPORTED'
      ? `${row.name}：连接可用（${result.latencyMs ?? 0}ms）`
      : `${row.name}：连接不可用（原因码 ${result.detailCode ?? 'UNKNOWN'}）`;
}

async function handleToggle(row: AiConnectorApi.Connector) {
  const enabled = row.status !== 'ENABLED';
  await updateConnectorStatus(row.id, row.version, enabled);
  showSuccessMessage(enabled ? '已启用' : '已停用');
  handleRefresh();
}

async function handleDelete(row: AiConnectorApi.Connector) {
  await deleteConnector(row.id, row.version);
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
          await getConnectorPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiConnectorApi.Connector>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <OperationsModal @success="handleRefresh" />
    <Grid table-title="连接器列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.create', ['连接器']),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: [AI_CONNECTOR_PERMISSIONS.create],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '连接测试',
              type: 'primary',
              link: true,
              auth: [AI_CONNECTOR_PERMISSIONS.probe],
              onClick: handleProbe.bind(null, row),
            },
            {
              label: '接口管理',
              type: 'primary',
              link: true,
              auth: [AI_CONNECTOR_PERMISSIONS.import],
              onClick: handleOperations.bind(null, row),
            },
            {
              label: row.status === 'ENABLED' ? '停用' : '启用',
              type: 'primary',
              link: true,
              auth: [AI_CONNECTOR_PERMISSIONS.update],
              onClick: handleToggle.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'primary',
              link: true,
              icon: ACTION_ICON.EDIT,
              auth: [AI_CONNECTOR_PERMISSIONS.update],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'danger',
              link: true,
              icon: ACTION_ICON.DELETE,
              auth: [AI_CONNECTOR_PERMISSIONS.delete],
              popConfirm: {
                title: `删除前会检查数据集与工具引用；确认删除 ${row.name}？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
    <p v-if="probeHint" class="px-4 pb-2 text-xs text-muted-foreground">
      {{ probeHint }}
    </p>
  </Page>
</template>
