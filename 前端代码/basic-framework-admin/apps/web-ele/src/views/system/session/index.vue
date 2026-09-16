<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SystemSessionApi } from '#/api/system/session';

import { Page } from '@vben/common-ui';
import { isEmpty } from '@vben/utils';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  getSessionPage,
  revokeSession,
  revokeSessionList,
} from '#/api/system/session';
import { useCrudDeleteActions } from '#/composables/use-crud-actions';
import { $t } from '#/locales';

import { useGridColumns, useGridFormSchema } from './data';

function handleRefresh() {
  gridApi.query();
}

const { checkedIds, handleDelete, handleDeleteBatch, handleRowCheckboxChange } =
  useCrudDeleteActions<SystemSessionApi.UserSession, number>({
    batchDeleteApi: revokeSessionList,
    deleteApi: revokeSession,
    getDeleteName: (row) => `会话 ${row.id}`,
    getRowKey: (row) => row.id,
    refresh: handleRefresh,
  });

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: { schema: useGridFormSchema() },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getSessionPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<SystemSessionApi.UserSession>,
  gridEvents: {
    checkboxAll: handleRowCheckboxChange,
    checkboxChange: handleRowCheckboxChange,
  },
});
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="用户会话">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '批量撤销',
              type: 'danger',
              icon: ACTION_ICON.DELETE,
              auth: ['system:session:revoke'],
              disabled: isEmpty(checkedIds),
              onClick: handleDeleteBatch,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '撤销',
              type: 'danger',
              link: true,
              icon: ACTION_ICON.DELETE,
              auth: ['system:session:revoke'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', ['会话']),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
