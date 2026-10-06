<script lang="ts" setup>
/**
 * 流程定义表单（X08）：标识与所属应用创建后不可修改。
 *
 * 历史运行固定引用版本编号，流程标识是运行记录的排障线索，所以改了会让在途运行对不上账。
 */
import type { AiWorkflowApi } from '#/api/ai/workflow';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { cloneDeep } from '@vben/utils';

import { useVbenForm } from '#/adapter/form';
import { createWorkflow, updateWorkflow } from '#/api/ai/workflow';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { useFormSchema } from '../data';

const emit = defineEmits<{ success: [] }>();

const editingId = ref<number>();
const editingVersion = ref<number>();

const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 140 },
  layout: 'horizontal',
  schema: useFormSchema(),
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    const values = cloneDeep(await formApi.getValues());
    const payload: AiWorkflowApi.WorkflowSaveReq = {
      applicationId: Number(values.applicationId),
      code: String(values.code),
      description: values.description ? String(values.description) : '',
      name: String(values.name),
    };
    if (editingId.value) {
      payload.id = editingId.value;
      payload.version = editingVersion.value;
      await updateWorkflow(payload);
      showSuccessMessage($t('ui.actionMessage.updateSuccess', [payload.name]));
    } else {
      await createWorkflow(payload);
      showSuccessMessage($t('ui.actionMessage.createSuccess', [payload.name]));
    }
    modalApi.close();
    emit('success');
  },
  onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData<AiWorkflowApi.Workflow>();
    if (data?.id) {
      editingId.value = data.id;
      editingVersion.value = data.version;
      formApi.setValues({ ...data });
      return;
    }
    editingId.value = undefined;
    editingVersion.value = undefined;
    formApi.resetForm();
  },
});
</script>

<template>
  <Modal :title="editingId ? '编辑流程定义' : '新增流程定义'" class="w-[640px]">
    <Form />
    <p class="px-4 pb-2 text-xs text-muted-foreground">
      标识在应用内唯一且创建后不可修改；流程本身不执行任何逻辑，真正的执行图在「版本管理」里维护。
    </p>
  </Modal>
</template>
