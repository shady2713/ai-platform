<script lang="ts" setup>
/** 工具表单（D10）：工具只承载身份与来源连接器；政策与 schema 在版本里。 */
import type { AiToolApi } from '#/api/ai/data';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { cloneDeep } from '@vben/utils';

import { useVbenForm } from '#/adapter/form';
import { createTool, updateTool } from '#/api/ai/data';
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
    const payload: AiToolApi.ToolSaveReq = {
      code: String(values.code),
      connectorId: Number(values.connectorId),
      description: values.description ? String(values.description) : '',
      name: String(values.name),
    };
    if (editingId.value) {
      payload.id = editingId.value;
      payload.version = editingVersion.value;
      await updateTool(payload);
      showSuccessMessage($t('ui.actionMessage.updateSuccess', [payload.name]));
    } else {
      await createTool(payload);
      showSuccessMessage($t('ui.actionMessage.createSuccess', [payload.name]));
    }
    modalApi.close();
    emit('success');
  },
  onOpenChange(isOpen) {
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData<AiToolApi.Tool>();
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
  <Modal :title="editingId ? '编辑工具' : '新增工具'" class="w-[640px]">
    <Form />
    <p class="px-4 pb-2 text-xs text-muted-foreground">
      工具本身不含可执行逻辑：执行政策、来源 operation 与输入输出 schema
      都放在版本里（不可变快照）。
    </p>
  </Modal>
</template>
