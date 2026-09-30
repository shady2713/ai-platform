<script lang="ts" setup>
/**
 * 跨系统主数据映射页面（Y02）：企业统一对象 + 映射版本 + 冲突/过期如实展示。
 *
 * 三条前端纪律：
 * 1. 判定与目录发现都要**显式选择版本与判定时刻**，页面不提供"用最新版本/当前时间"的省略入口；
 * 2. 冲突与过期照实展示（问题标注 + 冲突键 + 错误码文案），不在前端"帮用户挑一个"；
 * 3. 登记/发布走 ai:semantic:manage，无权用户看不到维护入口（后端仍会拒绝）。
 */
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiSemanticApi } from '#/api/ai/semantic';

import { ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  createMasterObject,
  getMasterObjectPage,
  updateMasterObject,
  updateMasterObjectStatus,
} from '#/api/ai/semantic';
import { showRequestError, showSuccessMessage } from '#/utils/feedback';

import {
  AI_SEMANTIC_PERMISSIONS,
  BLOCKING_HINT,
  NO_NAME_INFERENCE_HINT,
  useGridColumns,
  useGridFormSchema,
  useObjectFormSchema,
} from './data';
import Resolution from './modules/resolution.vue';
import Versions from './modules/versions.vue';

interface ObjectFormValues {
  description?: string;
  objectCode?: string;
  objectName: string;
  objectType: AiSemanticApi.MasterObjectType;
}

const { hasAccessByCodes } = useAccess();
const canManage = hasAccessByCodes([AI_SEMANTIC_PERMISSIONS.manage]);

const editing = ref<AiSemanticApi.MasterObject>();

const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  layout: 'horizontal',
  schema: useObjectFormSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [FormModal, formModalApi] = useVbenModal({
  onConfirm: handleSubmit,
  onOpenChange(isOpen: boolean) {
    if (isOpen) {
      const row = formModalApi.getData<AiSemanticApi.MasterObject>();
      editing.value = row;
      formApi.resetForm();
      if (row) {
        formApi.setValues(row);
      }
      formModalApi.setState({
        title: row ? `修改统一对象：${row.objectCode}` : '新建企业统一对象',
      });
      return;
    }
    editing.value = undefined;
  },
});

const [VersionsModal, versionsModalApi] = useVbenModal({
  connectedComponent: Versions,
  destroyOnClose: true,
});

const [ResolutionModal, resolutionModalApi] = useVbenModal({
  connectedComponent: Resolution,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData(undefined).open();
}

function handleEdit(row: AiSemanticApi.MasterObject) {
  formModalApi.setData(row).open();
}

function handleVersions(row: AiSemanticApi.MasterObject) {
  versionsModalApi.setData(row).open();
}

function handleResolution(row: AiSemanticApi.MasterObject) {
  resolutionModalApi.setData(row).open();
}

async function handleSubmit() {
  const { valid } = await formApi.validate();
  if (!valid) {
    return;
  }
  const values = (await formApi.getValues()) as ObjectFormValues;
  formModalApi.lock();
  try {
    if (editing.value) {
      await updateMasterObject({
        description: values.description,
        id: editing.value.id,
        objectName: values.objectName,
        objectType: values.objectType,
        version: editing.value.version,
      });
      showSuccessMessage('对象已更新（标识不可修改）');
    } else {
      await createMasterObject({
        description: values.description,
        objectCode: values.objectCode,
        objectName: values.objectName,
        objectType: values.objectType,
      });
      showSuccessMessage('对象已创建，请登记映射版本并发布');
    }
    formModalApi.close();
    handleRefresh();
  } catch (error) {
    showRequestError(error, '对象保存失败');
  } finally {
    formModalApi.unlock();
  }
}

async function handleToggleStatus(row: AiSemanticApi.MasterObject) {
  try {
    await updateMasterObjectStatus(
      row.id,
      row.version,
      row.status !== 'ACTIVE',
    );
    showSuccessMessage(
      row.status === 'ACTIVE' ? '已停用：判定将阻断' : '已启用',
    );
    handleRefresh();
  } catch (error) {
    showRequestError(error, '状态变更失败');
  }
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
          await getMasterObjectPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<AiSemanticApi.MasterObject>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal>
      <Form />
    </FormModal>
    <VersionsModal />
    <ResolutionModal />

    <Grid>
      <template #toolbar-tools>
        <TableAction
          v-if="canManage"
          :actions="[
            {
              icon: ACTION_ICON.ADD,
              label: '新建统一对象',
              type: 'primary',
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #action="{ row }">
        <TableAction
          :actions="[
            {
              label: '版本与映射',
              onClick: () => handleVersions(row),
            },
            {
              label: '判定与目录',
              onClick: () => handleResolution(row),
            },
          ]"
          :drops="
            canManage
              ? [
                  {
                    label: '编辑',
                    onClick: () => handleEdit(row),
                  },
                  {
                    label: row.status === 'ACTIVE' ? '停用' : '启用',
                    onClick: () => handleToggleStatus(row),
                  },
                ]
              : []
          "
        />
      </template>
    </Grid>

    <p class="mt-2 text-xs text-muted-foreground">
      {{ NO_NAME_INFERENCE_HINT }}
    </p>
    <p class="text-xs text-muted-foreground">{{ BLOCKING_HINT }}</p>
  </Page>
</template>
