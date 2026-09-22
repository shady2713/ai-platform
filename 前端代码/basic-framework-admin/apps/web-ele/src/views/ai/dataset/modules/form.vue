<script lang="ts" setup>
/** 数据集表单（D10）：标识与来源对象创建后不可修改（历史报表按版本编号引用）。 */
import type { AiDatasetApi } from '#/api/ai/data';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { cloneDeep } from '@vben/utils';

import { useVbenForm } from '#/adapter/form';
import { createDataset, updateDataset } from '#/api/ai/data';
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
    const payload: AiDatasetApi.DatasetSaveReq = {
      code: String(values.code),
      connectorId: Number(values.connectorId),
      description: values.description ? String(values.description) : '',
      name: String(values.name),
      sourceObject: String(values.sourceObject),
    };
    if (editingId.value) {
      payload.id = editingId.value;
      payload.version = editingVersion.value;
      await updateDataset(payload);
      showSuccessMessage($t('ui.actionMessage.updateSuccess', [payload.name]));
    } else {
      await createDataset(payload);
      showSuccessMessage($t('ui.actionMessage.createSuccess', [payload.name]));
    }
    modalApi.close();
    emit('success');
  },
  onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData<AiDatasetApi.Dataset>();
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
  <Modal :title="editingId ? '编辑数据集' : '新增数据集'" class="w-[640px]">
    <Form />
    <p class="px-4 pb-2 text-xs text-muted-foreground">
      来源对象必须已在该连接器的授权白名单内；数据集只声明"读哪个对象"，可读性由连接器保证。
    </p>
  </Modal>
</template>
