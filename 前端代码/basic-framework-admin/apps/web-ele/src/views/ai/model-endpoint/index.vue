<script lang="ts" setup>
/** AI 模型端点管理页面：列表、创建编辑、启停、轮换凭据与能力探测。 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiModelEndpointApi } from '#/api/ai/model-endpoint';

import { Page, useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteModelEndpoint,
  getModelEndpointPage,
  updateModelEndpointStatus,
} from '#/api/ai/model-endpoint';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import {
  AI_MODEL_ENDPOINT_PERMISSIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Form from './modules/form.vue';
import Probe from './modules/probe.vue';
import Rotate from './modules/rotate.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [RotateModal, rotateModalApi] = useVbenModal({
  connectedComponent: Rotate,
  destroyOnClose: true,
});

const [ProbeModal, probeModalApi] = useVbenModal({
  connectedComponent: Probe,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData({}).open();
}

function handleEdit(row: AiModelEndpointApi.ModelEndpoint) {
  formModalApi.setData(row).open();
}

function handleRotate(row: AiModelEndpointApi.ModelEndpoint) {
  rotateModalApi.setData(row).open();
}

function handleProbe(row: AiModelEndpointApi.ModelEndpoint) {
  probeModalApi.setData(row).open();
}

async function handleToggleStatus(row: AiModelEndpointApi.ModelEndpoint) {
  await updateModelEndpointStatus(row.id, row.version, !row.enabled);
  showSuccessMessage($t('ui.actionMessage.operationSuccess'));
  handleRefresh();
}

async function handleDelete(row: AiModelEndpointApi.ModelEndpoint) {
  await deleteModelEndpoint(row.id, row.version);
  showSuccessMessage($t('ui.actionMessage.deleteSuccess', [row.name]));
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) =>
          await getModelEndpointPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: true,
    },
  } as VxeTableGridOptions<AiModelEndpointApi.ModelEndpoint>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <RotateModal @success="handleRefresh" />
    <ProbeModal @success="handleRefresh" />
    <Grid table-title="模型端点列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.create', ['模型端点']),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: [AI_MODEL_ENDPOINT_PERMISSIONS.create],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '探测',
              type: 'primary',
              link: true,
              auth: [AI_MODEL_ENDPOINT_PERMISSIONS.probe],
              onClick: handleProbe.bind(null, row),
            },
            {
              label: row.enabled ? '停用' : '启用',
              type: 'primary',
              link: true,
              auth: [AI_MODEL_ENDPOINT_PERMISSIONS.update],
              popConfirm: {
                title: row.enabled
                  ? `停用后该端点不再接受新调用，确认停用 ${row.name}？`
                  : `确认启用 ${row.name}？`,
                confirm: handleToggleStatus.bind(null, row),
              },
            },
            {
              label: '轮换凭据',
              type: 'primary',
              link: true,
              auth: [AI_MODEL_ENDPOINT_PERMISSIONS.update],
              onClick: handleRotate.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'primary',
              link: true,
              icon: ACTION_ICON.EDIT,
              auth: [AI_MODEL_ENDPOINT_PERMISSIONS.update],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'danger',
              link: true,
              icon: ACTION_ICON.DELETE,
              disabled: row.referenced,
              auth: [AI_MODEL_ENDPOINT_PERMISSIONS.delete],
              popConfirm: {
                title: `删除后该端点无法恢复，确认删除 ${row.name}？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
