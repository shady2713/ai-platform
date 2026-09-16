<script lang="ts" setup>
import type { SystemDictTypeApi } from '#/api/system/dict/type';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import {
  createDictType,
  getDictType,
  updateDictType,
} from '#/api/system/dict/type';
import { $t } from '#/locales';
import { showSuccessMessage } from '#/utils/feedback';

import { useTypeFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<SystemDictTypeApi.DictType>();

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: {
      class: 'w-full',
    },
    formItemClass: 'col-span-2',
    labelWidth: 80,
  },
  layout: 'horizontal',
  schema: useTypeFormSchema(),
  showDefaultActions: false,
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    // 提交表单
    const data = (await formApi.getValues()) as SystemDictTypeApi.DictType;
    try {
      await (formData.value?.id ? updateDictType(data) : createDictType(data));
      // 关闭并提示
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
    const data = modalApi.getData<SystemDictTypeApi.DictType>();
    const isEdit = data?.id;
    modalApi.setState({
      title: isEdit
        ? $t('ui.actionTitle.edit', ['字典类型'])
        : $t('ui.actionTitle.create', ['字典类型']),
    });
    if (!data?.id) {
      return;
    }
    formData.value = data;
    modalApi.lock();
    try {
      formData.value = await getDictType(data.id);
      // 设置到 values
      await formApi.setValues(formData.value);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal>
    <Form class="mx-4" />
  </Modal>
</template>
