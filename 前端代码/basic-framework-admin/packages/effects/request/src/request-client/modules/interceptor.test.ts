import type {
  AxiosInstance,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios';

import { describe, expect, it, vi } from 'vitest';

import { InterceptorManager } from './interceptor';

function createRequestConfig(): InternalAxiosRequestConfig {
  return {
    headers: {} as InternalAxiosRequestConfig['headers'],
    method: 'GET',
    url: '/resource',
  };
}

function createAxiosInstance() {
  const requestUse = vi.fn();
  const responseUse = vi.fn();
  const instance = {
    interceptors: {
      request: { use: requestUse },
      response: { use: responseUse },
    },
  } as unknown as AxiosInstance;
  return { instance, requestUse, responseUse };
}

function createResponse(
  config: InternalAxiosRequestConfig,
): AxiosResponse<unknown> {
  return {
    config,
    data: { ok: true },
    headers: {},
    status: 200,
    statusText: 'OK',
  };
}

describe('interceptorManager', () => {
  it('registers and applies request interceptors in Axios order', async () => {
    const { instance } = createAxiosInstance();
    const manager = new InterceptorManager(instance);
    const calls: string[] = [];

    manager.addRequestInterceptor({
      fulfilled: (config) => {
        calls.push('first');
        return config;
      },
    });
    manager.addRequestInterceptor({
      fulfilled: async (config) => {
        calls.push('second');
        return config;
      },
    });

    const result = await manager.applyRequestInterceptors(
      createRequestConfig(),
    );

    expect(calls).toEqual(['second', 'first']);
    expect(result.url).toBe('/resource');
  });

  it('uses the default request handlers when none are supplied', async () => {
    const { instance, requestUse } = createAxiosInstance();
    const manager = new InterceptorManager(instance);
    const config = createRequestConfig();
    const error = new Error('request rejected');

    manager.addRequestInterceptor();

    const handlers = requestUse.mock.calls.at(0);
    if (!handlers?.[0] || !handlers[1]) {
      throw new Error('Default request interceptors were not registered');
    }
    const [fulfilled, rejected] = handlers;
    expect(fulfilled(config)).toBe(config);
    await expect(rejected(error)).rejects.toBe(error);
  });

  it('registers custom and default response handlers', async () => {
    const { instance, responseUse } = createAxiosInstance();
    const manager = new InterceptorManager(instance);
    const config = createRequestConfig();
    const response = createResponse(config);
    const error = new Error('response rejected');

    manager.addResponseInterceptor();
    const defaultHandlers = responseUse.mock.calls.at(0);
    if (!defaultHandlers?.[0] || !defaultHandlers[1]) {
      throw new Error('Default response interceptors were not registered');
    }
    const [defaultFulfilled, defaultRejected] = defaultHandlers;
    expect(defaultFulfilled(response)).toBe(response);
    await expect(defaultRejected(error)).rejects.toBe(error);

    const fulfilled = vi.fn(() => ({ ok: true }));
    const rejected = vi.fn();
    manager.addResponseInterceptor({ fulfilled, rejected });
    const customHandlers = responseUse.mock.calls.at(1);
    if (!customHandlers) {
      throw new Error('Custom response interceptors were not registered');
    }
    const [registeredFulfilled, registeredRejected] = customHandlers;

    expect(registeredFulfilled).toBe(fulfilled);
    expect(registeredRejected).toBe(rejected);
  });
});
