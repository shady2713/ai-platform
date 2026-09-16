import type { AxiosInstance, AxiosResponse } from 'axios';

import type {
  InterceptorRequestConfig,
  RequestInterceptorConfig,
  ResponseInterceptorConfig,
} from '../types';

type AxiosResponseFulfilled = NonNullable<
  Parameters<AxiosInstance['interceptors']['response']['use']>[0]
>;

const defaultRequestInterceptorConfig: RequestInterceptorConfig = {
  fulfilled: (response) => response,
  rejected: (error) => Promise.reject(error),
};

const defaultResponseInterceptorConfig: ResponseInterceptorConfig = {
  fulfilled: (response: AxiosResponse) => response,
  rejected: (error) => Promise.reject(error),
};

class InterceptorManager {
  private axiosInstance: AxiosInstance;
  private requestFulfilledHandlers: NonNullable<
    RequestInterceptorConfig['fulfilled']
  >[] = [];

  constructor(instance: AxiosInstance) {
    this.axiosInstance = instance;
  }

  addRequestInterceptor({
    fulfilled,
    rejected,
  }: RequestInterceptorConfig = defaultRequestInterceptorConfig) {
    if (fulfilled) {
      this.requestFulfilledHandlers.push(fulfilled);
    }
    this.axiosInstance.interceptors.request.use(fulfilled, rejected);
  }

  addResponseInterceptor<T = unknown>({
    fulfilled,
    rejected,
  }: ResponseInterceptorConfig<T> = defaultResponseInterceptorConfig) {
    // Axios 的拦截器声明只允许返回 AxiosResponse，但运行时支持将响应解包为业务数据。
    // 将这一处库类型不匹配集中在适配器边界，业务拦截器继续以 unknown 表达真实返回值。
    const axiosFulfilled = fulfilled as unknown as
      | AxiosResponseFulfilled
      | undefined;
    this.axiosInstance.interceptors.response.use(axiosFulfilled, rejected);
  }

  async applyRequestInterceptors(
    config: InterceptorRequestConfig,
  ): Promise<InterceptorRequestConfig> {
    let current = config;
    // Axios 后注册的请求拦截器先执行；SSE 保持相同的执行顺序。
    for (const fulfilled of this.requestFulfilledHandlers.toReversed()) {
      current = await fulfilled(current);
    }
    return current;
  }
}

export { InterceptorManager };
