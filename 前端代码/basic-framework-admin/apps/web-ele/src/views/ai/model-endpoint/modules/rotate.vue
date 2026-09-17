<script lang="ts" setup>
import type { AiModelEndpointApi } from '#/api/ai/model-endpoint';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import { rotateModelEndpointCredential } from '#/api/ai/model-endpoint';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { useRotateSchema } from '../data';

const emit = defineEmits(['success']);
const endpoint = ref<AiModelEndpointApi.ModelEndpoint>();

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-2',
    labelWidth: 90,
  },
  layout: 'horizontal',
  schema: useRotateSchema(),
  showDefaultActions: false,
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid || !endpoint.value) {
      return;
    }
    modalApi.lock();
    try {
      const { credential } = (await formApi.getValues()) as {
        credential: string;
      };
      await rotateModelEndpointCredential(
        endpoint.value.id,
        endpoint.value.version,
        credential,
      );
      await modalApi.close();
      emit('success');
      showSuccessMessage($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      endpoint.value = undefined;
      return;
    }
    endpoint.value = modalApi.getData<AiModelEndpointApi.ModelEndpoint>();
    await formApi.resetForm();
    modalApi.setState({ title: `轮换凭据：${endpoint.value?.name ?? ''}` });
  },
});
</script>

<template>
  <Modal class="w-[520px]">
    <Form />
  </Modal>
</template>
