<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import type { AuthApi } from '#/api/core/auth';

import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';

import { AuthenticationForgetPassword, z } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { logError } from '@vben/utils';

import {
  buildRequiredMobileSchema,
  buildRequiredPasswordSchema,
} from '#/adapter/form';
import { sendSmsCode, smsResetPassword } from '#/api/core/auth';
import { showSuccessMessage } from '#/utils/feedback';

defineOptions({ name: 'ForgetPassword' });

type ForgetPasswordFormValues = AuthApi.ResetPasswordParams &
  Record<string, unknown>;

const router = useRouter();

const loading = ref(false);
const CODE_LENGTH = 4;
const forgetPasswordRef = ref();

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: $t('authentication.mobile'),
      },
      fieldName: 'mobile',
      label: $t('authentication.mobile'),
      rules: buildRequiredMobileSchema($t('authentication.mobile')),
    },
    {
      component: 'VbenPinInput',
      componentProps: {
        codeLength: CODE_LENGTH,
        createText: (countdown: number) => {
          return countdown > 0
            ? $t('authentication.sendText', [countdown])
            : $t('authentication.sendCode');
        },
        placeholder: $t('authentication.code'),
        handleSendCode: async () => {
          loading.value = true;
          try {
            const formApi = forgetPasswordRef.value?.getFormApi();
            if (!formApi) {
              throw new Error('Form is not ready');
            }
            await formApi.validateField('mobile');
            const isMobileValid = await formApi.isFieldValid('mobile');
            if (!isMobileValid) {
              throw new Error('Invalid mobile');
            }
            const { mobile } = await formApi.getValues();
            const scene = 23;
            await sendSmsCode({ mobile, scene });
            showSuccessMessage('验证码发送成功');
          } finally {
            loading.value = false;
          }
        },
      },
      fieldName: 'code',
      label: $t('authentication.code'),
      rules: z.string().length(CODE_LENGTH, {
        message: $t('authentication.codeTip', [CODE_LENGTH]),
      }),
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        passwordStrength: true,
        placeholder: $t('authentication.password'),
      },
      fieldName: 'password',
      label: $t('authentication.password'),
      renderComponentContent() {
        return {
          strengthText: () => $t('authentication.passwordStrength'),
        };
      },
      rules: buildRequiredPasswordSchema($t('authentication.password')),
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        placeholder: $t('authentication.confirmPassword'),
      },
      dependencies: {
        rules(values) {
          const { password } = values;
          return buildRequiredPasswordSchema(
            $t('authentication.confirmPassword'),
          ).refine((value) => value === password, {
            message: $t('authentication.confirmPasswordTip'),
          });
        },
        triggerFields: ['password'],
      },
      fieldName: 'confirmPassword',
      label: $t('authentication.confirmPassword'),
    },
  ];
});

async function handleSubmit(values: Record<string, unknown>) {
  loading.value = true;
  try {
    if (!isForgetPasswordFormValues(values)) {
      throw new TypeError('重置密码表单字段类型无效');
    }
    const { mobile, code, password } = values;
    await smsResetPassword({ mobile, code, password });
    showSuccessMessage($t('authentication.resetPasswordSuccess'));
    await router.push('/');
  } catch (error) {
    logError('auth:forget-password:submit', error);
  } finally {
    loading.value = false;
  }
}

function isForgetPasswordFormValues(
  values: Record<string, unknown>,
): values is ForgetPasswordFormValues {
  return (
    typeof values.mobile === 'string' &&
    typeof values.code === 'string' &&
    typeof values.password === 'string'
  );
}
</script>

<template>
  <AuthenticationForgetPassword
    ref="forgetPasswordRef"
    :form-schema="formSchema"
    :loading="loading"
    @submit="handleSubmit"
  />
</template>
