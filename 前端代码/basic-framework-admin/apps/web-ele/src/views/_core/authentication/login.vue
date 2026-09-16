<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import type { AuthApi } from '#/api/core/auth';

import { computed, ref } from 'vue';

import { AuthenticationLogin, Verification } from '@vben/common-ui';
import { isCaptchaEnable } from '@vben/hooks';
import { $t } from '@vben/locales';
import { logError } from '@vben/utils';

import { ElAlert, ElButton, ElDialog, ElInput } from 'element-plus';

import {
  buildLoginPasswordSchema,
  buildRequiredUsernameSchema,
  isPasswordValue,
} from '#/adapter/form';
import {
  AUTH_PASSWORD_EXPIRED_CODE,
  changeExpiredPasswordApi,
  checkCaptcha,
  getCaptcha,
} from '#/api/core/auth';
import { useAuthStore } from '#/store';
import { showRequestError, showSuccessMessage } from '#/utils/feedback';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();
const captchaEnable = isCaptchaEnable();

const loginRef = ref();
const verifyRef = ref();
const captchaType = 'blockPuzzle';

const passwordChangeVisible = ref(false);
const passwordChangeLoading = ref(false);
const passwordChangeUsername = ref('');
const oldPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const canSubmitPasswordChange = computed(
  () =>
    oldPassword.value.length > 0 &&
    isPasswordValue(newPassword.value) &&
    newPassword.value !== oldPassword.value &&
    confirmPassword.value === newPassword.value,
);

async function handleLogin(values: Record<string, unknown>) {
  if (captchaEnable) {
    verifyRef.value.show();
    return;
  }
  try {
    await submitCredentials(values);
  } catch (error) {
    logError('auth:login:submit', error);
  }
}

async function handleVerifySuccess({
  captchaVerification,
}: {
  captchaVerification: string;
}) {
  try {
    await submitCredentials({
      ...(await loginRef.value.getFormApi().getValues()),
      captchaVerification,
    });
  } catch (error) {
    logError('auth:login:verify', error);
  }
}

async function submitCredentials(values: AuthApi.LoginParams) {
  try {
    await authStore.authLogin('username', values);
  } catch (error) {
    if (isPasswordExpiredError(error) && values.username && values.password) {
      openPasswordChangeDialog(values.username, values.password);
      return;
    }
    // loginApi 声明了 errorMode: 'silent'，常规失败由本页内联提示接管
    showRequestError(error, resolveLoginFallbackMessage(error));
    throw error;
  }
}

// silent 模式下全局拦截器不展示断网/超时提示，页面侧按错误文本映射同款文案
function resolveLoginFallbackMessage(error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  if (message.includes('Network Error')) {
    return $t('ui.fallback.http.networkError');
  }
  if (message.includes('timeout')) {
    return $t('ui.fallback.http.requestTimeout');
  }
  return $t('ui.fallback.http.internalServerError');
}

function isPasswordExpiredError(error: unknown) {
  const response = (
    error as undefined | { response?: { data?: { code?: number } } }
  )?.response;
  return response?.data?.code === AUTH_PASSWORD_EXPIRED_CODE;
}

function openPasswordChangeDialog(username: string, password: string) {
  passwordChangeUsername.value = username;
  oldPassword.value = password;
  newPassword.value = '';
  confirmPassword.value = '';
  passwordChangeVisible.value = true;
}

async function submitPasswordChange() {
  if (!canSubmitPasswordChange.value) return;
  passwordChangeLoading.value = true;
  try {
    const username = passwordChangeUsername.value;
    const changedPassword = newPassword.value;
    await changeExpiredPasswordApi({
      username,
      oldPassword: oldPassword.value,
      newPassword: changedPassword,
    });
    resetPasswordChangeDialog();
    // 复用登录表单与已输入的用户名，直接进入正常登录流程
    await loginRef.value
      ?.getFormApi?.()
      .setValues({ password: changedPassword });
    if (captchaEnable) {
      // 验证码启用时无法自动重登，提示用户使用新密码手动登录
      showSuccessMessage($t('authentication.expiredPasswordSuccess'));
    } else {
      showSuccessMessage($t('authentication.expiredPasswordSuccessAutoLogin'));
      await submitCredentials({ username, password: changedPassword });
    }
  } catch (error) {
    // 旧密码错误等失败提示由全局拦截器展示，对话框保持打开以便重试
    logError('auth:login:change-expired-password', error);
  } finally {
    passwordChangeLoading.value = false;
  }
}

function resetPasswordChangeDialog() {
  passwordChangeVisible.value = false;
  passwordChangeUsername.value = '';
  oldPassword.value = '';
  newPassword.value = '';
  confirmPassword.value = '';
}

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: $t('authentication.usernameTip'),
      },
      fieldName: 'username',
      label: $t('authentication.username'),
      rules: buildRequiredUsernameSchema($t('authentication.username')),
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        placeholder: $t('authentication.passwordTip'),
      },
      fieldName: 'password',
      label: $t('authentication.password'),
      rules: buildLoginPasswordSchema($t('authentication.password')),
    },
  ];
});
</script>

<template>
  <div>
    <AuthenticationLogin
      ref="loginRef"
      :form-schema="formSchema"
      :loading="authStore.loginLoading"
      @submit="handleLogin"
    />
    <Verification
      ref="verifyRef"
      v-if="captchaEnable"
      :captcha-type="captchaType"
      :check-captcha-api="checkCaptcha"
      :get-captcha-api="getCaptcha"
      :img-size="{ width: '400px', height: '200px' }"
      mode="pop"
      @on-success="handleVerifySuccess"
    />
    <ElDialog
      v-model="passwordChangeVisible"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      :title="$t('authentication.expiredPasswordTitle')"
      width="min(92vw, 460px)"
    >
      <ElAlert :closable="false" class="mb-5" show-icon type="warning">
        {{ $t('authentication.expiredPasswordAlert') }}
      </ElAlert>
      <ElInput
        v-model="oldPassword"
        :aria-label="$t('authentication.oldPasswordTip')"
        autocomplete="current-password"
        :placeholder="$t('authentication.oldPasswordTip')"
        type="password"
      />
      <ElInput
        v-model="newPassword"
        :aria-label="$t('authentication.newPasswordPolicyTip')"
        autocomplete="new-password"
        class="mt-3"
        :placeholder="$t('authentication.newPasswordPolicyTip')"
        type="password"
      />
      <ElInput
        v-model="confirmPassword"
        :aria-label="$t('authentication.confirmNewPasswordTip')"
        autocomplete="new-password"
        class="mt-3"
        :placeholder="$t('authentication.confirmNewPasswordTip')"
        type="password"
      />
      <ElButton
        class="mt-4 w-full"
        :disabled="!canSubmitPasswordChange"
        :loading="passwordChangeLoading"
        type="primary"
        @click="submitPasswordChange"
      >
        {{ $t('authentication.changePasswordAndLogin') }}
      </ElButton>
      <ElButton class="mt-2 w-full" @click="resetPasswordChangeDialog">
        {{ $t('authentication.backToLogin') }}
      </ElButton>
    </ElDialog>
  </div>
</template>
