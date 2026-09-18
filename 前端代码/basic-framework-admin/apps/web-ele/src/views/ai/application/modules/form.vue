<script lang="ts" setup>
import type { AiApplicationApi } from '#/api/ai/application';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  createApplication,
  getApplication,
  updateApplication,
} from '#/api/ai/application';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { parseOrigins, useFormSchema } from '../data';

interface ApplicationFormValues {
  appCode?: string;
  credential?: string;
  description?: string;
  id?: number;
  name: string;
  originsText: string;
  version?: number;
}

const emit = defineEmits(['success', 'secret']);
const formData = ref<AiApplicationApi.Application>();

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

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    try {
      const values = (await formApi.getValues()) as ApplicationFormValues;
      const payload: AiApplicationApi.ApplicationSaveReq = {
        appCode: values.appCode,
        description: values.description,
        id: values.id,
        name: values.name,
        origins: parseOrigins(values.originsText ?? ''),
        version: values.version,
      };
      if (values.credential) {
        payload.credential = values.credential;
      }
      if (formData.value?.id) {
        await updateApplication(payload);
        showSuccessMessage($t('ui.actionMessage.operationSuccess'));
      } else {
        // 创建成功：把一次性秘密交给父组件用弹窗展示，并在本组件内立即丢弃
        const issue = await createApplication(payload);
        emit('secret', { appCode: issue.appCode, secret: issue.secret });
      }
      await modalApi.close();
      emit('success');
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      formData.value = undefined;
      return;
    }
    const row = modalApi.getData<AiApplicationApi.Application>();
    if (!row?.id) {
      await formApi.resetForm();
      modalApi.setState({ title: $t('ui.actionTitle.create', ['应用']) });
      return;
    }
    modalApi.setState({ title: $t('ui.actionTitle.edit', ['应用']) });
    const detail = await getApplication(row.id);
    formData.value = detail;
    await formApi.setValues({
      appCode: detail.appCode,
      description: detail.description,
      id: detail.id,
      name: detail.name,
      originsText: (detail.origins ?? []).join('\n'),
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
