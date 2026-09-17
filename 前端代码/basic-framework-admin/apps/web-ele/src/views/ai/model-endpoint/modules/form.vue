<script lang="ts" setup>
import type { AiModelEndpointApi } from '#/api/ai/model-endpoint';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  createModelEndpoint,
  getModelEndpoint,
  updateModelEndpoint,
} from '#/api/ai/model-endpoint';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<AiModelEndpointApi.ModelEndpoint>();

/** 表单值形状：与 useFormSchema 的字段一一对应（凭据只在提交非空时携带） */
interface ModelEndpointFormValues {
  baseUrl: string;
  capabilities: string[];
  credential?: string;
  id?: number;
  modelId: string;
  name: string;
  provider: string;
  version?: number;
}

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-2',
    labelWidth: 90,
  },
  layout: 'horizontal',
  schema: useFormSchema(),
  showDefaultActions: false,
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    try {
      const values = (await formApi.getValues()) as ModelEndpointFormValues;
      const payload: AiModelEndpointApi.ModelEndpointSaveReq = {
        capabilities: values.capabilities,
        baseUrl: values.baseUrl,
        id: values.id,
        modelId: values.modelId,
        name: values.name,
        provider: values.provider,
        version: values.version,
      };
      // 凭据只提交非空值：留空表示保留已有凭据，编辑时不回填旧值
      if (values.credential) {
        payload.credential = values.credential;
      }
      await (formData.value?.id
        ? updateModelEndpoint(payload)
        : createModelEndpoint(payload));
      await modalApi.close();
      emit('success');
      showSuccessMessage($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      formData.value = undefined;
      return;
    }
    const row = modalApi.getData<AiModelEndpointApi.ModelEndpoint>();
    if (!row?.id) {
      await formApi.resetForm();
      modalApi.setState({ title: $t('ui.actionTitle.create', ['模型端点']) });
      return;
    }
    modalApi.setState({ title: $t('ui.actionTitle.edit', ['模型端点']) });
    const detail = await getModelEndpoint(row.id);
    formData.value = detail;
    await formApi.setValues({
      capabilities: detail.capabilities,
      baseUrl: detail.baseUrl,
      id: detail.id,
      modelId: detail.modelId,
      name: detail.name,
      provider: detail.provider,
      version: detail.version,
    });
  },
});
</script>

<template>
  <Modal class="w-[640px]">
    <Form />
  </Modal>
</template>
