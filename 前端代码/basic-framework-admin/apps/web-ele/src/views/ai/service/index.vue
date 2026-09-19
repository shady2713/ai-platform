<script lang="ts" setup>
/**
 * AI 服务配置页面（S05）：草稿编辑、发布与评测、版本历史与回退、调试。
 *
 * 发布/回退入口受 `ai:service:activate` 控制（无权用户看不到按钮，后端仍二次校验）；
 * 调试与发布结果分开呈现，避免把调试输出误当成线上结果。
 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiServiceApi } from '#/api/ai/service';

import { ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  checkServiceCapabilities,
  deleteService,
  getServicePage,
  markServiceReady,
} from '#/api/ai/service';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import {
  AI_SERVICE_PERMISSIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Debug from './modules/debug.vue';
import Form from './modules/form.vue';
import Release from './modules/release.vue';
import Versions from './modules/versions.vue';

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [ReleaseModal, releaseModalApi] = useVbenModal({
  connectedComponent: Release,
  destroyOnClose: true,
});
const [VersionsModal, versionsModalApi] = useVbenModal({
  connectedComponent: Versions,
  destroyOnClose: true,
});
const [DebugModal, debugModalApi] = useVbenModal({
  connectedComponent: Debug,
  destroyOnClose: true,
});

/** 能力校验结果提示（可发布范围 = 端点声明 ∩ 探测确认） */
const capabilityHint = ref('');

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData({}).open();
}

function handleEdit(row: AiServiceApi.Service) {
  formModalApi.setData(row).open();
}

function handleRelease(row: AiServiceApi.Service) {
  releaseModalApi.setData(row).open();
}

function handleVersions(row: AiServiceApi.Service) {
  versionsModalApi.setData(row).open();
}

function handleDebug(row: AiServiceApi.Service) {
  debugModalApi.setData(row).open();
}

async function handleMarkReady(row: AiServiceApi.Service) {
  const capability = await checkServiceCapabilities(row.id);
  if (!capability.satisfied) {
    capabilityHint.value = `缺少能力：${capability.missing.join('、')}（端点探测未确认）`;
    return;
  }
  await markServiceReady(row.id, row.version);
  capabilityHint.value = '';
  showSuccessMessage('已标记可发布');
  handleRefresh();
}

async function handleDelete(row: AiServiceApi.Service) {
  await deleteService(row.id, row.version);
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
          await getServicePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiServiceApi.Service>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <ReleaseModal @success="handleRefresh" />
    <VersionsModal @success="handleRefresh" />
    <DebugModal />
    <Grid table-title="服务列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('ui.actionTitle.create', ['服务']),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: [AI_SERVICE_PERMISSIONS.create],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '标记可发布',
              type: 'primary',
              link: true,
              auth: [AI_SERVICE_PERMISSIONS.publish],
              onClick: handleMarkReady.bind(null, row),
            },
            {
              label: '发布与评测',
              type: 'primary',
              link: true,
              auth: [AI_SERVICE_PERMISSIONS.release],
              onClick: handleRelease.bind(null, row),
            },
            {
              label: '版本历史与回退',
              type: 'primary',
              link: true,
              auth: [AI_SERVICE_PERMISSIONS.activate],
              onClick: handleVersions.bind(null, row),
            },
            {
              label: '调试',
              type: 'primary',
              link: true,
              auth: [AI_SERVICE_PERMISSIONS.debug],
              onClick: handleDebug.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'primary',
              link: true,
              icon: ACTION_ICON.EDIT,
              auth: [AI_SERVICE_PERMISSIONS.update],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'danger',
              link: true,
              icon: ACTION_ICON.DELETE,
              auth: [AI_SERVICE_PERMISSIONS.delete],
              popConfirm: {
                title: `删除后无法恢复（历史发布版本与评测记录保留），确认删除 ${row.name}？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
    <p v-if="capabilityHint" class="px-3 pb-2 text-xs text-destructive">
      {{ capabilityHint }}
    </p>
  </Page>
</template>
