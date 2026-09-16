import type { RequestClient } from '../request-client';
import type { InterceptorRequestConfig, SseRequestOptions } from '../types';

import { AxiosHeaders } from 'axios';

/**
 * SSE模块
 */
class SSE {
  private client: RequestClient;

  constructor(client: RequestClient) {
    this.client = client;
  }

  public async postSSE(
    url: string,
    data?: unknown,
    requestOptions?: SseRequestOptions,
  ) {
    return this.requestSSE(url, data, {
      ...requestOptions,
      method: 'POST',
    });
  }

  /**
   * SSE请求方法
   * @param url - 请求URL
   * @param data - 请求数据
   * @param requestOptions - SSE请求选项
   */
  public async requestSSE(
    url: string,
    data?: unknown,
    requestOptions?: SseRequestOptions,
  ) {
    const baseUrl = this.client.getBaseUrl() || '';

    const initialConfig: InterceptorRequestConfig = {
      url,
      method: requestOptions?.method ?? 'GET',
      headers: new AxiosHeaders(),
    };
    const axiosConfig = await this.client.prepareRequestConfig(initialConfig);

    const merged = new Headers();
    Object.entries(axiosConfig.headers.toJSON()).forEach(([key, value]) => {
      if (value !== null && value !== undefined) {
        merged.set(key, String(value));
      }
    });
    if (requestOptions?.headers) {
      new Headers(requestOptions.headers).forEach((v, k) => merged.set(k, v));
    }
    if (!merged.has('accept')) {
      merged.set('accept', 'text/event-stream');
    }

    const ct = (merged.get('content-type') || '').toLowerCase();
    const body = normalizeRequestBody(requestOptions?.body ?? data, ct);
    const requestInit: RequestInit = {
      ...requestOptions,
      method: axiosConfig.method,
      headers: merged,
      body,
    };

    const response = await fetch(safeJoinUrl(baseUrl, url), requestInit);
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();

    if (!reader) {
      throw new Error('No reader');
    }
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          decoder.decode(new Uint8Array(0), { stream: false });
          requestOptions?.onEnd?.();
          break;
        }
        const content = decoder.decode(value, { stream: true });
        requestOptions?.onMessage?.(content);
      }
    } finally {
      reader.releaseLock();
    }
  }
}

function isNativeRequestBody(value: unknown): value is BodyInit {
  return (
    typeof value === 'string' ||
    value instanceof ArrayBuffer ||
    ArrayBuffer.isView(value) ||
    value instanceof Blob ||
    value instanceof FormData ||
    value instanceof ReadableStream ||
    value instanceof URLSearchParams
  );
}

function normalizeRequestBody(
  value: unknown,
  contentType: string,
): BodyInit | null | undefined {
  if (value === null || value === undefined || isNativeRequestBody(value)) {
    return value;
  }
  if (typeof value === 'object' && contentType.includes('application/json')) {
    return JSON.stringify(value);
  }
  throw new TypeError('SSE request body must be a valid BodyInit value');
}

function safeJoinUrl(baseUrl: string | undefined, url: string): string {
  if (!baseUrl) {
    return url; // 没有 baseUrl，直接返回 url
  }

  // 如果 url 本身就是绝对地址，直接返回
  if (/^https?:\/\//i.test(url)) {
    return url;
  }

  // 如果 baseUrl 是完整 URL，就用 new URL
  if (/^https?:\/\//i.test(baseUrl)) {
    return new URL(url, baseUrl).toString();
  }

  // 否则，当作路径拼接
  return `${baseUrl.replace(/\/+$/, '')}/${url.replace(/^\/+/, '')}`;
}

export { SSE };
