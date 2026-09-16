import type { AuthPermissionInfo, UserInfo } from '@vben/types';

import type { AuthApi } from '#/api';

import { ref } from 'vue';
import { useRouter } from 'vue-router';

import { resetAccessibleRoutes } from '@vben/access';
import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';
import { resetAllStores, useAccessStore, useUserStore } from '@vben/stores';

import { defineStore } from 'pinia';

import {
  getAuthPermissionInfoApi,
  loginApi,
  logoutApi,
  refreshTokenApi,
} from '#/api';
import { $t } from '#/locales';
import { showSuccessNotification } from '#/utils/feedback';

/**
 * 登出标记的会话存储键：内存态标志无法跨页面刷新存活，
 * 登出后若刷新接口调用失败，HttpOnly Cookie 仍在服务端有效，
 * 刷新页面时 restoreSession 必须凭此标记放弃恢复（ADR 0038）。
 */
const LOGGED_OUT_STORAGE_KEY = 'basic-framework:logged-out';

function markLoggedOut() {
  try {
    sessionStorage.setItem(LOGGED_OUT_STORAGE_KEY, '1');
  } catch {
    // sessionStorage 不可用（隐私模式等）时退化为内存态语义
  }
}

function hasLoggedOutMarker() {
  try {
    return sessionStorage.getItem(LOGGED_OUT_STORAGE_KEY) === '1';
  } catch {
    // 读取失败视为无标记，保持原有恢复行为
    return false;
  }
}

function clearLoggedOutMarker() {
  try {
    sessionStorage.removeItem(LOGGED_OUT_STORAGE_KEY);
  } catch {
    // 不可用时无标记可清理
  }
}

export const useAuthStore = defineStore('auth', () => {
  const accessStore = useAccessStore();
  const userStore = useUserStore();
  const router = useRouter();

  const loginLoading = ref(false);
  const sessionRestoreAttempted = ref(false);
  let authenticationVersion = 0;
  let sessionRestorePromise: Promise<boolean> | undefined;

  /**
   * 异步处理登录操作
   * Asynchronously handle the login process
   * @param type 登录类型
   * @param params 登录表单数据
   * @param onSuccess 登录成功后的回调函数
   */
  async function authLogin(
    type: 'username',
    params: AuthApi.LoginParams,
    onSuccess?: () => Promise<void> | void,
  ) {
    const version = ++authenticationVersion;
    let userInfo: null | UserInfo = null;
    try {
      let loginResult: AuthApi.LoginResult;
      loginLoading.value = true;
      switch (type) {
        case 'username': {
          // 用户名密码登录的表单已强制校验密码必填，此处 password 必然存在
          if (params.password === undefined) {
            throw new Error('password is required for account login');
          }
          loginResult = await loginApi(params);
          break;
        }
        default: {
          throw new Error(`Unsupported login type: ${type}`);
        }
      }
      if (version !== authenticationVersion) {
        throw new Error('Authentication changed while logging in');
      }
      userInfo = await completeLogin(loginResult, onSuccess);
      return { loginResult, userInfo };
    } finally {
      loginLoading.value = false;
    }
  }

  async function completeLogin(
    loginResult: AuthApi.LoginResult,
    onSuccess?: () => Promise<void> | void,
  ) {
    const wasExpired = accessStore.loginExpired;
    const previousUserId = userStore.userInfo?.id;
    const { accessToken } = loginResult;

    if (!accessToken) {
      throw new Error('Login response did not include an access token');
    }
    resetAccessibleRoutes(router);
    resetAllStores();
    accessStore.setLoginExpired(wasExpired);
    accessStore.setAccessToken(accessToken);
    clearLoggedOutMarker();
    loginLoading.value = true;
    sessionRestoreAttempted.value = true;
    const permissionInfo = await fetchUserInfo();
    const userInfo = permissionInfo.user;

    accessStore.setLoginExpired(false);
    if (onSuccess) {
      await onSuccess();
    } else if (wasExpired && previousUserId === userInfo.id) {
      await router.replace({
        path: router.currentRoute.value.fullPath,
        force: true,
      });
    } else {
      await router.push(preferences.app.defaultHomePath);
    }

    if (userInfo?.nickname) {
      showSuccessNotification({
        message: `${$t('authentication.loginSuccessDesc')}:${userInfo.nickname}`,
        duration: 3,
        title: $t('authentication.loginSuccess'),
      });
    }
    return userInfo;
  }

  /** 退出登录，重置所有 store 状态并跳转到登录页 */
  async function logout(redirect: boolean = true) {
    const accessToken = accessStore.accessToken;
    resetAccessibleRoutes(router);
    resetAllStores();
    sessionRestoreAttempted.value = true;
    accessStore.setLoginExpired(false);
    markLoggedOut();
    const version = authenticationVersion;
    try {
      await logoutApi(accessToken);
    } catch {
      // Local state stays signed out even if the server cannot clear its cookie.
    }
    if (version !== authenticationVersion) return;

    // 回登录页带上当前路由地址
    const currentRoute = router.currentRoute.value;
    await router.replace({
      path: LOGIN_PATH,
      query:
        redirect && currentRoute.path !== LOGIN_PATH
          ? {
              redirect: encodeURIComponent(currentRoute.fullPath),
            }
          : {},
    });
  }

  /** 获取当前用户权限信息并更新到各个 store 中 */
  async function fetchUserInfo() {
    const version = authenticationVersion;
    const wasExpired = accessStore.loginExpired;
    try {
      const authPermissionInfo: AuthPermissionInfo =
        await getAuthPermissionInfoApi();
      if (!accessStore.accessToken || version !== authenticationVersion) {
        throw new Error('Authentication changed while loading permissions');
      }
      userStore.setUserInfo(authPermissionInfo.user);
      userStore.setUserRoles(authPermissionInfo.roles);
      accessStore.setAccessMenus(authPermissionInfo.menus);
      accessStore.setAccessCodes(authPermissionInfo.permissions);
      return authPermissionInfo;
    } catch (error) {
      if (version === authenticationVersion) {
        resetAccessibleRoutes(router);
        resetAllStores();
        accessStore.setLoginExpired(wasExpired);
        sessionRestoreAttempted.value = true;
      }
      throw error;
    }
  }

  async function restoreSession() {
    if (sessionRestorePromise) return sessionRestorePromise;
    if (sessionRestoreAttempted.value) {
      return Boolean(accessStore.accessToken);
    }
    if (hasLoggedOutMarker()) {
      // 本标签页已显式登出：即使 Cookie 仍有效也不再恢复会话
      sessionRestoreAttempted.value = true;
      return false;
    }
    sessionRestoreAttempted.value = true;
    const version = authenticationVersion;
    const restoration = (async () => {
      try {
        const response = await refreshTokenApi();
        const accessToken = response?.data?.data?.accessToken;
        if (!accessToken || version !== authenticationVersion) return false;
        accessStore.setAccessToken(accessToken);
        return true;
      } catch {
        return false;
      }
    })().finally(() => {
      if (sessionRestorePromise === restoration)
        sessionRestorePromise = undefined;
    });
    sessionRestorePromise = restoration;
    return sessionRestorePromise;
  }

  function $reset() {
    authenticationVersion++;
    sessionRestorePromise = undefined;
    loginLoading.value = false;
    sessionRestoreAttempted.value = false;
  }

  return {
    $reset,
    authLogin,
    fetchUserInfo,
    loginLoading,
    logout,
    restoreSession,
  };
});
