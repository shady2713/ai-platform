<script lang="ts" setup>
/** AI 应用管理页面（A09）：创建/编辑、精确 Origin、启停、凭据轮换与吊销，秘密只展示一次。 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiApplicationApi } from '#/api/ai/application';

import { ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteApplication,
  getApplicationPage,
  revokeCredential,
  updateApplicationStatus,
} from '#/api/ai/application';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import {
  AI_APPLICATION_PERMISSIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Form from './modules/form.vue';
import Rotate from './modules/rotate.vue';
import Secret from './modules/secret.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [RotateModal, rotateModalApi] = useVbenModal({
  connectedComponent: Rotate,
  destroyOnClose: true,
});
const [SecretModal, secretModalApi] = useVbenModal({
  connectedComponent: Secret,
  destroyOnClose: true,
});

/** 一次性秘密只经弹窗数据传入，父组件不保存明文 */
const pendingSecret = ref<{ appCode: string; secret: string }>();

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData({}).open();
}

function handleEdit(row: AiApplicationApi.Application) {
  formModalApi.setData(row).open();
}

function handleRotate(row: AiApplicationApi.Application) {
  rotateModalApi.setData(row).open();
}

function handleSecret(payload: { appCode: string; secret: string }) {
  pendingSecret.value = payload;
  secretModalApi.setData(payload).open();
  showSuccessMessage('凭据已签发，请立即保存');
}

async function handleToggleStatus(row: AiApplicationApi.Application) {
  await updateApplicationStatus(row.id, row.version, !row.enabled);
  showSuccessMessage($t('ui.actionMessage.operationSuccess'));
  handleRefresh();
}

async function handleRevoke(row: AiApplicationApi.Application) {
  await revokeCredential(row.id, row.version);
  showSuccessMessage('凭据已吊销，旧秘密立即失效');
  handleRefresh();
}

async function handleDelete(row: AiApplicationApi.Application) {
  await deleteApplication(row.id, row.version);
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
          await getApplicationPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiApplicationApi.Application>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" @secret="handleSecret" />
    <RotateModal @secret="handleSecret" />
    <SecretModal />
    <Grid table-title="应用列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.create', ['应用']),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: [AI_APPLICATION_PERMISSIONS.create],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '轮换凭据',
              type: 'primary',
              link: true,
              auth: [AI_APPLICATION_PERMISSIONS.rotate],
              onClick: handleRotate.bind(null, row),
            },
            {
              label: '吊销凭据',
              type: 'primary',
              link: true,
              auth: [AI_APPLICATION_PERMISSIONS.revoke],
              disabled: !row.credentialConfigured,
              popConfirm: {
                title: `吊销后 ${row.name} 的旧秘密立即失效，确认吊销？`,
                confirm: handleRevoke.bind(null, row),
              },
            },
            {
              label: row.enabled ? '停用' : '启用',
              type: 'primary',
              link: true,
              auth: [AI_APPLICATION_PERMISSIONS.update],
              popConfirm: {
                title: row.enabled
                  ? `停用后 ${row.name} 无法换票与调用，确认停用？`
                  : `确认启用 ${row.name}？`,
                confirm: handleToggleStatus.bind(null, row),
              },
            },
            {
              label: $t('common.edit'),
              type: 'primary',
              link: true,
              icon: ACTION_ICON.EDIT,
              auth: [AI_APPLICATION_PERMISSIONS.update],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'danger',
              link: true,
              icon: ACTION_ICON.DELETE,
              auth: [AI_APPLICATION_PERMISSIONS.delete],
              popConfirm: {
                title: `删除后无法恢复（需先吊销凭据），确认删除 ${row.name}？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
