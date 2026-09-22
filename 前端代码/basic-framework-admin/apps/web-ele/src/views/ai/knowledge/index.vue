<script lang="ts" setup>
/**
 * AI 知识库页面（K09）：知识库、文档入库/版本/重试/删除、检索调试。
 *
 * 三件事在界面上必须可分：
 *  - **状态**：排队/解析/切分/可用/失败/删除中/需 OCR 都有可读文案（data.ts 映射，未知状态原样展示）；
 *  - **权限**：写操作按 `ai:knowledge:*` 权限码显示（无管理权时按钮不可见，且后端仍会校验）；
 *  - **证据**：检索调试展示后端返回的真实引用（含位置与片段），原文预览走受控 API。
 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiKnowledgeApi } from '#/api/ai/knowledge';

import { Page, useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteKnowledgeBase,
  getKnowledgeBasePage,
  updateKnowledgeBaseStatus,
} from '#/api/ai/knowledge';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import {
  AI_KNOWLEDGE_PERMISSIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Debug from './modules/debug.vue';
import Documents from './modules/documents.vue';
import Form from './modules/form.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [DocumentsModal, documentsModalApi] = useVbenModal({
  connectedComponent: Documents,
  destroyOnClose: true,
});
const [DebugModal, debugModalApi] = useVbenModal({
  connectedComponent: Debug,
  destroyOnClose: true,
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
          return await getKnowledgeBasePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id' },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiKnowledgeApi.KnowledgeBase>,
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData({}).open();
}

function handleEdit(row: AiKnowledgeApi.KnowledgeBase) {
  formModalApi.setData(row).open();
}

function handleDocuments(row: AiKnowledgeApi.KnowledgeBase) {
  documentsModalApi.setData(row).open();
}

function handleDebug(row: AiKnowledgeApi.KnowledgeBase) {
  debugModalApi.setData(row).open();
}

async function handleToggle(row: AiKnowledgeApi.KnowledgeBase) {
  const enabled = row.status !== 'ENABLED';
  await updateKnowledgeBaseStatus(row.id, row.version, enabled);
  showSuccessMessage(enabled ? '已启用' : '已停用（不再接受新入库）');
  handleRefresh();
}

async function handleDelete(row: AiKnowledgeApi.KnowledgeBase) {
  await deleteKnowledgeBase(row.id, row.version);
  showSuccessMessage($t('ui.actionMessage.deleteSuccess', [row.name]));
  handleRefresh();
}
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DocumentsModal />
    <DebugModal />
    <Grid table-title="AI 知识库">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.create', ['知识库']),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: [AI_KNOWLEDGE_PERMISSIONS.create],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '文档',
              icon: ACTION_ICON.VIEW,
              auth: [AI_KNOWLEDGE_PERMISSIONS.query],
              onClick: handleDocuments.bind(null, row),
            },
            {
              label: '检索调试',
              icon: ACTION_ICON.SEARCH,
              auth: [AI_KNOWLEDGE_PERMISSIONS.debug],
              onClick: handleDebug.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: $t('ui.actionTitle.edit'),
              icon: ACTION_ICON.EDIT,
              auth: [AI_KNOWLEDGE_PERMISSIONS.update],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: row.status === 'ENABLED' ? '停用' : '启用',
              icon: ACTION_ICON.EDIT,
              auth: [AI_KNOWLEDGE_PERMISSIONS.update],
              popConfirm: {
                title:
                  row.status === 'ENABLED'
                    ? '停用后不再接受新入库，确认？'
                    : '确认启用？',
                confirm: handleToggle.bind(null, row),
              },
            },
            {
              label: $t('ui.actionTitle.delete'),
              icon: ACTION_ICON.DELETE,
              auth: [AI_KNOWLEDGE_PERMISSIONS.delete],
              popConfirm: {
                title: '被服务引用或仍有文档时后端会拒绝，确认删除？',
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
