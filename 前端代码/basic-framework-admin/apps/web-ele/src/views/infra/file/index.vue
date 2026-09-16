<script lang="ts" setup>
/** 文件管理页面 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { InfraFileApi } from '#/api/infra/file';

import { Page, useVbenModal } from '@vben/common-ui';
import { isEmpty } from '@vben/utils';

import { useClipboard } from '@vueuse/core';
import { ElButton } from 'element-plus';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { deleteFile, deleteFileList, getFilePage } from '#/api/infra/file';
import { useCrudActions } from '#/composables/use-crud-actions';
import { $t } from '#/locales';
import { showErrorMessage, showSuccessMessage } from '#/utils/feedback';

import { useGridColumns, useGridFormSchema } from './data';
import { openFile } from './file-access';
import FileImage from './modules/file-image.vue';
import Form from './modules/form.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

/** 刷新表格 */
function handleRefresh() {
  clearCheckedIds();
  gridApi.query();
}

const {
  checkedIds,
  clearCheckedIds,
  getGridEvents,
  handleCreate: handleUpload,
  handleDelete,
  handleDeleteBatch,
} = useCrudActions<InfraFileApi.File>({
  batchDeleteApi: deleteFileList,
  deleteApi: deleteFile,
  getDeleteName: (row) => row.name || row.path || '',
  modalApi: formModalApi,
  refresh: handleRefresh,
});

/** 复制链接到剪贴板 */
const { copy } = useClipboard({ legacy: true });
async function handleCopyUrl(row: InfraFileApi.File) {
  if (!row.url) {
    showErrorMessage('文件 URL 为空');
    return;
  }

  try {
    await copy(row.url);
    showSuccessMessage('复制成功');
  } catch {
    showErrorMessage('复制失败');
  }
}

/** 预览/下载文件：读取或打开失败时给出反馈，避免静默无响应 */
async function handleOpenFile(row: InfraFileApi.File) {
  try {
    await openFile(row);
  } catch {
    showErrorMessage('文件打开失败');
  }
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
        query: async ({ page }, formValues) => {
          return await getFilePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
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
  } as VxeTableGridOptions<InfraFileApi.File>,
  gridEvents: getGridEvents(),
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <Grid table-title="文件列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '上传文件',
              type: 'primary',
              icon: ACTION_ICON.UPLOAD,
              onClick: handleUpload,
            },
            {
              label: $t('ui.actionTitle.deleteBatch'),
              type: 'danger',
              icon: ACTION_ICON.DELETE,
              disabled: isEmpty(checkedIds),
              auth: ['infra:file:delete'],
              onClick: handleDeleteBatch,
            },
          ]"
        />
      </template>
      <template #file-content="{ row }">
        <FileImage v-if="row.type && row.type.includes('image')" :row="row" />
        <ElButton type="primary" link @click="() => handleOpenFile(row)">
          {{
            row.type &&
            row.type !== 'image/svg+xml' &&
            (row.type.includes('pdf') || row.type.includes('image'))
              ? '预览'
              : '下载'
          }}
        </ElButton>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '复制链接',
              type: 'primary',
              link: true,
              icon: ACTION_ICON.COPY,
              onClick: handleCopyUrl.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'danger',
              link: true,
              icon: ACTION_ICON.DELETE,
              auth: ['infra:file:delete'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [row.name]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
