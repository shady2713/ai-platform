<script lang="ts" setup>
/** 资源授权页面（A09）：列表、新增、修改动作白名单与撤销；变更提示作用范围，入口按权限码显隐。 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiGrantApi } from '#/api/ai/grant';

import { Page, useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { getGrantPage, revokeGrant } from '#/api/ai/grant';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { PERMISSIONS, useGridColumns, useGridFormSchema } from './data';
import Form from './modules/form.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData({}).open();
}

function handleEdit(row: AiGrantApi.Grant) {
  formModalApi.setData(row).open();
}

async function handleRevoke(row: AiGrantApi.Grant) {
  await revokeGrant(row.id, row.version);
  showSuccessMessage('授权已撤销，新请求与历史产物读取立即被拒绝');
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
          await getGrantPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiGrantApi.Grant>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <Grid table-title="资源授权列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.create', ['授权']),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: [PERMISSIONS.create],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '修改动作',
              type: 'primary',
              link: true,
              icon: ACTION_ICON.EDIT,
              auth: [PERMISSIONS.update],
              disabled: row.status !== 'ACTIVE',
              onClick: handleEdit.bind(null, row),
            },
            {
              label: '撤销',
              type: 'danger',
              link: true,
              auth: [PERMISSIONS.revoke],
              disabled: row.status !== 'ACTIVE',
              popConfirm: {
                title: `撤销后 ${row.resourceKey} 的新请求、后续工具步骤与产物读取都会被拒绝，确认撤销？`,
                confirm: handleRevoke.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
