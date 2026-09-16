<script setup lang="ts">
import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { $t } from '@vben/locales';
import { logError } from '@vben/utils';

import {
  buildLoginPasswordSchema,
  buildRequiredPasswordSchema,
  useVbenForm,
} from '#/adapter/form';
import { updateUserPassword } from '#/api/system/user/profile';
import { showSuccessMessage } from '#/utils/feedback';

type ResetPasswordFormValues = Record<string, unknown> &
  SystemUserProfileApi.UpdatePasswordReqVO & {
    confirmPassword: string;
  };

const [Form, formApi] = useVbenForm({
  commonConfig: {
    labelWidth: 70,
  },
  schema: [
    {
      component: 'VbenInputPassword',
      componentProps: {
        placeholder: $t('authentication.password'),
      },
      fieldName: 'oldPassword',
      label: '旧密码',
      rules: buildLoginPasswordSchema('旧密码'),
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        passwordStrength: true,
        placeholder: '请输入新密码',
      },
      dependencies: {
        rules(values) {
          return buildRequiredPasswordSchema('新密码').refine(
            (value) => value !== values.oldPassword,
            '新旧密码不能相同',
          );
        },
        triggerFields: ['newPassword', 'oldPassword'],
      },
      fieldName: 'newPassword',
      label: '新密码',
      rules: 'passwordRequired',
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        passwordStrength: true,
        placeholder: $t('authentication.confirmPassword'),
      },
      dependencies: {
        rules(values) {
          return buildRequiredPasswordSchema('确认密码').refine(
            (value) => value === values.newPassword,
            '新密码和确认密码不一致',
          );
        },
        triggerFields: ['newPassword', 'confirmPassword'],
      },
      fieldName: 'confirmPassword',
      label: '确认密码',
      rules: 'passwordRequired',
    },
  ],
  resetButtonOptions: {
    show: false,
  },
  submitButtonOptions: {
    content: '修改密码',
  },
  handleSubmit,
});

async function handleSubmit(values: Record<string, unknown>) {
  try {
    formApi.setLoading(true);
    if (!isResetPasswordFormValues(values)) {
      throw new TypeError('密码表单字段类型无效');
    }
    await updateUserPassword({
      oldPassword: values.oldPassword,
      newPassword: values.newPassword,
    });
    showSuccessMessage($t('ui.actionMessage.operationSuccess'));
  } catch (error) {
    logError('profile:reset-password:submit', error);
  } finally {
    formApi.setLoading(false);
  }
}

function isResetPasswordFormValues(
  values: Record<string, unknown>,
): values is ResetPasswordFormValues {
  return (
    typeof values.oldPassword === 'string' &&
    typeof values.newPassword === 'string' &&
    typeof values.confirmPassword === 'string'
  );
}
</script>

<template>
  <div class="mt-4 md:w-full lg:w-1/2 2xl:w-2/5">
    <Form />
  </div>
</template>
