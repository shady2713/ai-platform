/**
 * 该文件可自行根据业务逻辑进行调整
 */
import type { RequestClientOptions } from '@vben/request';

import { useAppConfig } from '@vben/hooks';
import { preferences } from '@vben/preferences';
import {
  authenticateResponseInterceptor,
  defaultResponseInterceptor,
  errorMessageResponseInterceptor,
  RequestClient,
} from '@vben/request';
import { useAccessStore } from '@vben/stores';
import { logWarn } from '@vben/utils';

import { useAuthStore } from '#/store';
import { showRequestError } from '#/utils/feedback';

import { refreshTokenApi } from './core';

const { apiURL } = useAppConfig(import.meta.env, import.meta.env.PROD);

function failedAuthorization(error: unknown) {
  return (error as { config?: { headers?: { Authorization?: string } } })
    ?.config?.headers?.Authorization;
}

function createRequestClient(baseURL: string, options?: RequestClientOptions) {
  const client = new RequestClient({
    ...options,
    baseURL,
  });

  // 凭据只在同源于 baseURL 的请求上附带：跨源绝对 URL（如对象存储直链）
  // 必须剥离 Authorization，杜绝 Bearer 令牌外发给第三方域
  const baseOrigin = new URL(baseURL, window.location.origin).origin;
  function isCredentialEligible(config: { url?: string }) {
    // 浏览器解析网络 URL 时把反斜杠视同斜杠：先归一再判定，
    // 防止 \\host\path 形态绕过绝对 URL 检查后被浏览器解析为跨域地址
    const url = (config.url ?? '').replaceAll('\\', '/');
    // 与 axios isAbsoluteURL 对齐：scheme:// 与协议相对 //host/path 都是绝对 URL，
    // 不拼 baseURL 直发目标主机；其余形态（相对路径）由 baseURL 解析，必然内部
    if (!/^(?:[a-z][a-z\d+\-.]*:)?\/\//i.test(url)) {
      return true;
    }
    try {
      return new URL(url, window.location.origin).origin === baseOrigin;
    } catch {
      return false;
    }
  }

  /**
   * 重新认证逻辑
   */
  async function doReAuthenticate(error: unknown) {
    const accessStore = useAccessStore();
    const authorization = failedAuthorization(error);
    if (
      authorization &&
      authorization !== formatToken(accessStore.accessToken)
    ) {
      return;
    }
    logWarn('Authentication expired');
    const authStore = useAuthStore();
    accessStore.setAccessToken(null);
    if (
      preferences.app.loginExpiredMode === 'modal' &&
      accessStore.isAccessChecked
    ) {
      accessStore.setLoginExpired(true);
    } else {
      await authStore.logout();
    }
  }

  /**
   * 刷新token逻辑
   */
  async function doRefreshToken(error: unknown) {
    const accessStore = useAccessStore();
    const previousToken = accessStore.accessToken;
    if (!previousToken) {
      throw new Error('No active session to refresh');
    }
    const authorization = failedAuthorization(error);
    if (authorization && authorization !== formatToken(previousToken)) {
      throw new Error('Authentication changed before refreshing the session');
    }
    const resp = await refreshTokenApi();
    if (previousToken !== accessStore.accessToken) {
      throw new Error('Authentication changed while refreshing the session');
    }
    const newToken = resp?.data?.data?.accessToken;
    if (!newToken) {
      throw new Error('Refresh token response did not include an access token');
    }
    accessStore.setAccessToken(newToken);
    return newToken;
  }

  function formatToken(token: null | string) {
    return token ? `Bearer ${token}` : null;
  }

  // 请求头处理
  client.addRequestInterceptor({
    fulfilled: async (config) => {
      const accessStore = useAccessStore();
      const authorization = formatToken(accessStore.accessToken);
      if (!isCredentialEligible(config)) {
        delete config.headers.Authorization;
        config.withCredentials = false;
        config.headers['Accept-Language'] = preferences.app.locale;
        return config;
      }
      if (
        config.__isRetryRequest &&
        config.headers.Authorization !== authorization
      ) {
        throw new Error('Authentication changed before retrying the request');
      }
      config.headers.Authorization = authorization;
      config.headers['Accept-Language'] = preferences.app.locale;

      return config;
    },
  });

  // 处理返回的响应数据格式
  client.addResponseInterceptor(
    defaultResponseInterceptor({
      codeField: 'code',
      dataField: 'data',
      successCode: 0,
    }),
  );

  // token过期的处理
  client.addResponseInterceptor(
    authenticateResponseInterceptor({
      client,
      doReAuthenticate,
      doRefreshToken,
      enableRefreshToken: preferences.app.enableRefreshToken,
      formatToken,
      shouldAuthenticate: (error) =>
        isCredentialEligible(
          (error as { config?: { url?: string } }).config ?? {},
        ),
    }),
  );

  // 通用的错误处理,如果没有进入上面的错误处理逻辑，就会进入这里
  client.addResponseInterceptor(
    errorMessageResponseInterceptor((msg: string, error) => {
      showRequestError(error, msg);
    }),
  );

  return client;
}

export const requestClient = createRequestClient(apiURL, {
  responseReturn: 'data',
  withCredentials: true,
});

export const baseRequestClient = new RequestClient({
  baseURL: apiURL,
  withCredentials: true,
});

export { createRequestClient };
