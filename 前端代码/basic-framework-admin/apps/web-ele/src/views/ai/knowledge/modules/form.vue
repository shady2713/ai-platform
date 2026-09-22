<script lang="ts" setup>
/** 知识库表单（K09）：标识/可见性/嵌入模型与维度创建后不可修改（编辑态禁用）。 */
import type { AiKnowledgeApi } from '#/api/ai/knowledge';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { cloneDeep } from '@vben/utils';

import { useVbenForm } from '#/adapter/form';
import { createKnowledgeBase, updateKnowledgeBase } from '#/api/ai/knowledge';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { useFormSchema } from '../data';

const emit = defineEmits<{ success: [] }>();

const editingId = ref<number>();
const editingVersion = ref<number>();
const isEdit = computed(() => Boolean(editingId.value));

const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 140 },
  layout: 'horizontal',
  schema: useFormSchema(false),
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
    const payload: AiKnowledgeApi.KnowledgeBaseSaveReq = {
      code: String(values.code),
      embeddingDimension: Number(values.embeddingDimension),
      embeddingModel: String(values.embeddingModel),
      name: String(values.name),
      visibility: String(values.visibility ?? 'SHARED'),
    };
    if (values.description) {
      payload.description = String(values.description);
    }
    if (values.ownerApplicationId) {
      payload.ownerApplicationId = Number(values.ownerApplicationId);
    }
    if (values.retentionDays) {
      payload.retentionDays = Number(values.retentionDays);
    }
    if (editingId.value) {
      payload.id = editingId.value;
      payload.version = editingVersion.value;
      await updateKnowledgeBase(payload);
      showSuccessMessage($t('ui.actionMessage.updateSuccess', [payload.name]));
    } else {
      await createKnowledgeBase(payload);
      showSuccessMessage($t('ui.actionMessage.createSuccess', [payload.name]));
    }
    modalApi.close();
    emit('success');
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      editingId.value = undefined;
      editingVersion.value = undefined;
      return;
    }
    const data = modalApi.getData<AiKnowledgeApi.KnowledgeBase>();
    formApi.resetForm();
    if (data?.id) {
      editingId.value = data.id;
      editingVersion.value = data.version;
      await formApi.setValues(data);
    }
  },
});
</script>

<template>
  <Modal :title="isEdit ? '编辑知识库' : '新增知识库'" class="w-[640px]">
    <Form />
  </Modal>
</template>
