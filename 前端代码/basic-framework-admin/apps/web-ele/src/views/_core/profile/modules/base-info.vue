<script setup lang="ts">
import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { watch } from 'vue';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { $t } from '@vben/locales';
import { logError } from '@vben/utils';

import {
  buildOptionalEmailSchema,
  buildOptionalMobileSchema,
  useVbenForm,
  z,
} from '#/adapter/form';
import { updateUserProfile } from '#/api/system/user/profile';
import { showSuccessMessage } from '#/utils/feedback';

const props = defineProps<{
  profile?: SystemUserProfileApi.UserProfileRespVO;
}>();
const emit = defineEmits<{
  (e: 'success'): void;
}>();

const [Form, formApi] = useVbenForm({
  commonConfig: {
    labelWidth: 70,
  },
  schema: [
    {
      label: '用户昵称',
      fieldName: 'nickname',
      component: 'Input',
      componentProps: {
        placeholder: '请输入用户昵称',
      },
      rules: 'required',
    },
    {
      label: '用户手机',
      fieldName: 'mobile',
      component: 'Input',
      componentProps: {
        placeholder: '请输入用户手机',
      },
      rules: buildOptionalMobileSchema('手机号'),
    },
    {
      label: '用户邮箱',
      fieldName: 'email',
      component: 'Input',
      componentProps: {
        placeholder: '请输入用户邮箱',
      },
      rules: buildOptionalEmailSchema('邮箱'),
    },
    {
      label: '用户性别',
      fieldName: 'sex',
      component: 'RadioGroup',
      componentProps: {
        options: getDictOptions(DICT_TYPE.SYSTEM_USER_SEX, 'number'),
      },
      rules: z.number(),
    },
  ],
  resetButtonOptions: {
    show: false,
  },
  submitButtonOptions: {
    content: '更新信息',
  },
  handleSubmit,
});

async function handleSubmit(values: SystemUserProfileApi.UpdateProfileReqVO) {
  try {
    formApi.setLoading(true);
    await updateUserProfile(values);
    emit('success');
    showSuccessMessage($t('ui.actionMessage.operationSuccess'));
  } catch (error) {
    logError('profile:base-info:submit', error);
  } finally {
    formApi.setLoading(false);
  }
}

watch(
  () => props.profile,
  (newProfile) => {
    if (newProfile) {
      formApi.setValues(newProfile);
    }
  },
  { immediate: true },
);
</script>

<template>
  <div class="mt-4 md:w-full lg:w-1/2 2xl:w-2/5">
    <Form />
  </div>
</template>
