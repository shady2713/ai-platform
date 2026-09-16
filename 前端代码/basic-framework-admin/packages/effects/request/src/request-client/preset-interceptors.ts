import type { RequestClient } from './request-client';
import type {
  ErrorMode,
  MakeErrorMessageFn,
  RequestClientConfig,
  ResponseInterceptorConfig,
} from './types';

import { $t } from '@vben/locales';
import { isFunction, logError } from '@vben/utils';

import axios from 'axios';

type ResponseBody = Record<string, unknown>;
type RetryRequestConfig = RequestClientConfig & {
  headers: Record<string, unknown>;
  url: string;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function requestFailure(error: unknown):
  | undefined
  | {
      config: RetryRequestConfig;
      status?: number;
    } {
  if (!isRecord(error) || !isRecord(error.config)) {
    return undefined;
  }
  const response = isRecord(error.response) ? error.response : undefined;
  const status =
    typeof response?.status === 'number' ? response.status : undefined;
  const config = error.config;
  if (!isRecord(config.headers)) {
    config.headers = {};
  }
  if (typeof config.url !== 'string') {
    config.url = '';
  }
  return {
    config: config as unknown as RetryRequestConfig,
    status,
  };
}

function errorMode(error: unknown): ErrorMode {
  if (!isRecord(error)) {
    return 'global';
  }
  const directConfig = isRecord(error.config) ? error.config : undefined;
  const response = isRecord(error.response) ? error.response : undefined;
  const responseConfig =
    response && isRecord(response.config) ? response.config : undefined;
  const mode = directConfig?.errorMode ?? responseConfig?.errorMode;
  return mode === 'inline' || mode === 'silent' ? mode : 'global';
}

function errorStatus(error: unknown): number | undefined {
  if (!isRecord(error) || !isRecord(error.response)) {
    return undefined;
  }
  return typeof error.response.status === 'number'
    ? error.response.status
    : undefined;
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export const defaultResponseInterceptor = ({
  codeField = 'code',
  dataField = 'data',
  successCode = 0,
}: {
  /** 响应数据中代表访问结果的字段名 */
  codeField: string;
  /** 响应数据中装载实际数据的字段名，或者提供一个函数从响应数据中解析需要返回的数据 */
  dataField: ((response: ResponseBody) => unknown) | string;
  /** 当codeField所指定的字段值与successCode相同时，代表接口访问成功。如果提供一个函数，则返回true代表接口访问成功 */
  successCode: ((code: unknown) => boolean) | number | string;
}): ResponseInterceptorConfig<ResponseBody> => {
  return {
    fulfilled: (response) => {
      const { config, data: responseData, status } = response;

      if (config.responseReturn === 'raw') {
        return response;
      }

      if (status >= 200 && status < 400) {
        if (config.responseReturn === 'body') {
          return responseData;
        } else if (
          isFunction(successCode)
            ? successCode(responseData[codeField])
            : responseData[codeField] === successCode
        ) {
          return isFunction(dataField)
            ? dataField(responseData)
            : responseData[dataField];
        }
      }
      throw Object.assign({}, response, { response });
    },
  };
};

export const authenticateResponseInterceptor = ({
  client,
  doReAuthenticate,
  doRefreshToken,
  enableRefreshToken,
  formatToken,
  shouldAuthenticate = () => true,
}: {
  client: RequestClient;
  doReAuthenticate: (error: unknown) => Promise<void>;
  doRefreshToken: (error: unknown) => Promise<string>;
  enableRefreshToken: boolean;
  formatToken: (token: string) => null | string;
  /** 只有属于当前认证域的请求才允许刷新或清除会话。 */
  shouldAuthenticate?: (error: unknown) => boolean;
}): ResponseInterceptorConfig => {
  return {
    rejected: async (error) => {
      const failure = requestFailure(error);
      // 401 仅以 HTTP status 判定（ADR 0003），body code 不再作为认证信号；非 401 直接抛出
      if (failure?.status !== 401 || !shouldAuthenticate(error)) {
        throw error;
      }
      const { config } = failure;
      // 判断是否启用了 refreshToken 功能
      // 如果没有启用或者已经是重试请求了，直接跳转到重新登录
      if (!enableRefreshToken || config.__isRetryRequest) {
        await doReAuthenticate(error);
        throw error;
      }
      // 如果正在刷新 token，则将请求加入队列，等待刷新完成
      if (client.isRefreshing) {
        config.__isRetryRequest = true;
        return new Promise((resolve, reject) => {
          client.refreshTokenQueue.push({
            reject: () => reject(error),
            resolve: (newToken: string) => {
              config.headers.Authorization = formatToken(newToken);
              resolve(client.request(config.url, { ...config }));
            },
          });
        });
      }

      // 标记开始刷新 token
      client.isRefreshing = true;
      // 标记当前请求为重试请求，避免无限循环
      config.__isRetryRequest = true;

      try {
        const newToken = await doRefreshToken(error);
        config.headers.Authorization = formatToken(newToken);

        // 处理队列中的请求
        client.refreshTokenQueue.forEach((callback) =>
          callback.resolve(newToken),
        );
        // 清空队列
        client.refreshTokenQueue = [];

        return client.request(config.url, { ...config });
      } catch (refreshError) {
        // 如果刷新 token 失败，处理错误（如强制登出或跳转登录页面）
        client.refreshTokenQueue.forEach((callback) => callback.reject());
        client.refreshTokenQueue = [];
        logError('request:refresh-token', refreshError);
        await doReAuthenticate(error);

        // 保留原始 401，让后续错误策略仍能识别认证失败并避免重复提示。
        throw error;
      } finally {
        client.isRefreshing = false;
      }
    },
  };
};

export const errorMessageResponseInterceptor = (
  makeErrorMessage?: MakeErrorMessageFn,
): ResponseInterceptorConfig => {
  return {
    rejected: (error: unknown) => {
      if (axios.isCancel(error)) {
        return Promise.reject(error);
      }

      if (errorMode(error) !== 'global') {
        return Promise.reject(error);
      }

      const err = errorText(error);
      let errMsg = '';
      if (err?.includes('Network Error')) {
        errMsg = $t('ui.fallback.http.networkError');
      } else if (err.includes('timeout')) {
        errMsg = $t('ui.fallback.http.requestTimeout');
      }
      if (errMsg) {
        makeErrorMessage?.(errMsg, error);
        return Promise.reject(error);
      }

      let errorMessage = '';
      // 归一化为 HTTP status：传输/认证语义只看 status（ADR 0003），body code 仅作业务子原因展示
      const status = errorStatus(error);

      switch (status) {
        case 400: {
          errorMessage = $t('ui.fallback.http.badRequest');
          break;
        }
        case 401: {
          errorMessage = $t('ui.fallback.http.unauthorized');
          break;
        }
        case 403: {
          errorMessage = $t('ui.fallback.http.forbidden');
          break;
        }
        case 404: {
          errorMessage = $t('ui.fallback.http.notFound');
          break;
        }
        case 408: {
          errorMessage = $t('ui.fallback.http.requestTimeout');
          break;
        }
        case 409: {
          errorMessage = $t('ui.fallback.http.conflict');
          break;
        }
        case 422: {
          errorMessage = $t('ui.fallback.http.unprocessableEntity');
          break;
        }
        case 429: {
          errorMessage = $t('ui.fallback.http.tooManyRequests');
          break;
        }
        case 503: {
          errorMessage = $t('ui.fallback.http.serviceUnavailable');
          break;
        }
        default: {
          errorMessage = $t('ui.fallback.http.internalServerError');
        }
      }
      makeErrorMessage?.(errorMessage, error);
      return Promise.reject(error);
    },
  };
};
