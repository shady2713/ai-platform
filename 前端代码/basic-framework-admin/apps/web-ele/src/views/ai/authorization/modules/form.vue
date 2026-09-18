<script lang="ts" setup>
/**
 * 授权新增/修改弹窗（A09）：展示授权变更的作用范围提示；
 * 资源标识提供"已授权资源"选项（只列有权项），新标识必须显式输入并二次确认。
 */
import type { AiGrantApi } from '#/api/ai/grant';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import { createGrant, getGrantPage, updateGrant } from '#/api/ai/grant';
import { $t } from '#/locales';
import { showConfirmDialog, showSuccessMessage } from '#/utils/feedback';

import {
  buildResourceOptions,
  NEW_RESOURCE_CONFIRM,
  SCOPE_WARNING,
  useFormSchema,
} from '../data';

interface GrantFormValues {
  actions: string[];
  applicationId: string;
  externalUserId?: string;
  id?: number;
  resourceKey: string;
  resourceType: string;
  subjectType: string;
  version?: number;
}

const emit = defineEmits(['success']);
const editing = ref<AiGrantApi.Grant>();
const resourceOptions = ref<Array<{ label: string; value: string }>>([]);

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-2',
    labelWidth: 100,
  },
  layout: 'horizontal',
  schema: useFormSchema(),
  showDefaultActions: false,
});

/** 载入该主体已有授权的资源标识，作为可选资源（只列有权项）。 */
async function loadResourceOptions(
  applicationId?: number,
  subjectType?: string,
  externalUserId?: string,
) {
  if (!applicationId || !subjectType) {
    resourceOptions.value = [];
    return;
  }
  const page = await getGrantPage({
    pageNo: 1,
    pageSize: 100,
    applicationId,
    subjectType,
    externalUserId,
  });
  resourceOptions.value = buildResourceOptions(page.list ?? []);
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    const values = (await formApi.getValues()) as GrantFormValues;
    const applicationId = Number(values.applicationId);
    if (editing.value) {
      // 修改只允许改动作白名单
      await updateGrant({
        actions: values.actions,
        id: editing.value.id,
        version: values.version ?? editing.value.version,
      });
      showSuccessMessage($t('ui.actionMessage.operationSuccess'));
    } else {
      // 以当前表单里的应用/主体为准刷新"已授权资源"选项：新建入口也能只列有权项
      await loadResourceOptions(
        applicationId,
        values.subjectType,
        values.externalUserId,
      );
      const known = resourceOptions.value.some(
        (option) => option.value === values.resourceKey,
      );
      if (!known) {
        // 新资源标识必须显式确认（提示作用范围）
        const { action } = await showConfirmDialog(
          `${NEW_RESOURCE_CONFIRM}\n\n${SCOPE_WARNING}`,
          { title: '确认新增资源授权' },
        );
        if (action !== 'confirm') {
          return;
        }
      }
      await createGrant({
        actions: values.actions,
        applicationId,
        externalUserId: values.externalUserId,
        resourceKey: values.resourceKey,
        resourceType: values.resourceType,
        subjectType: values.subjectType,
      });
      showSuccessMessage(
        '授权已生效，撤销后新请求与历史产物读取都会立即被拒绝',
      );
    }
    await modalApi.close();
    emit('success');
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      editing.value = undefined;
      resourceOptions.value = [];
      return;
    }
    const row = modalApi.getData<AiGrantApi.Grant>();
    editing.value = row;
    if (row?.id) {
      modalApi.setState({ title: `修改授权动作：${row.resourceKey}` });
      await loadResourceOptions(
        row.applicationId,
        row.subjectType,
        row.externalUserId,
      );
      await formApi.setValues({
        actions: row.actions,
        applicationId: String(row.applicationId),
        externalUserId: row.externalUserId,
        id: row.id,
        resourceKey: row.resourceKey,
        resourceType: row.resourceType,
        subjectType: row.subjectType,
        version: row.version,
      });
      return;
    }
    await formApi.resetForm();
    modalApi.setState({ title: $t('ui.actionTitle.create', ['授权']) });
  },
});
</script>

<template>
  <Modal class="w-[640px]">
    <div class="mb-3 text-sm text-amber-600" data-test="grant-scope-warning">
      {{ SCOPE_WARNING }}
    </div>
    <Form />
  </Modal>
</template>
