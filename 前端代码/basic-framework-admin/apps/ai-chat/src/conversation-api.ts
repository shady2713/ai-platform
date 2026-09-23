import type { ConversationApi, ConversationSummary } from '@vben/ai-chat-ui';

import type { TicketStorage } from './client-factory';

import { AiChatApiError } from '@vben/ai-embed-sdk';

/**
 * 会话接口端口实现（C02）：独立 Chat 应用走**应用端**会话接口（O01 契约）。
 *
 * <p>为什么单独一层：会话 composable 只依赖端口（可单测、可换宿主），
 * 具体请求与票据读取留在应用侧；错误统一走 `parseCommonResult`（与开放客户端同一口径）。
 */
export interface ConversationApiOptions {
  /** 应用端基址，例如 https://host/app-api */
  baseUrl: string;
  fetchImpl?: typeof fetch;
  ticketStorage?: Pick<Storage, 'getItem'> | TicketStorage | undefined;
}

interface ConversationResp {
  conversationKey: string;
  id: number;
  title: string;
  updateTime?: string;
}

export function createConversationApi(
  options: ConversationApiOptions,
): ConversationApi {
  const base = options.baseUrl.replace(/\/+$/, '');
  const doFetch = options.fetchImpl ?? globalThis.fetch;

  async function request<T>(path: string, init: RequestInit): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    if (init.body) {
      headers.set('Content-Type', 'application/json');
    }
    // 票据只进请求头：URL 与请求体里不出现
    const token = options.ticketStorage?.getItem('ai_access_ticket') ?? null;
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    const response = await doFetch(`${base}${path}`, { ...init, headers });
    const contentType = response.headers.get('content-type') ?? '';
    const payload = contentType.includes('application/json')
      ? await response.json()
      : await response.text();
    return parseEnvelope<T>(response.status, payload);
  }

  function toSummary(resp: ConversationResp): ConversationSummary {
    return {
      conversationKey: resp.conversationKey,
      id: resp.id,
      title: resp.title,
      ...(resp.updateTime === undefined ? {} : { updateTime: resp.updateTime }),
    };
  }

  return {
    async create(title?: string): Promise<ConversationSummary> {
      const created = await request<ConversationResp>(
        '/ai/conversation/create',
        {
          body: JSON.stringify(title ? { title } : {}),
          method: 'POST',
        },
      );
      return toSummary(created);
    },

    async list(): Promise<ConversationSummary[]> {
      const page = await request<{ list: ConversationResp[] }>(
        '/ai/conversation/page',
        {
          method: 'GET',
        },
      );
      return (page.list ?? []).map((item) => toSummary(item));
    },

    async remove(id: number): Promise<void> {
      await request<unknown>('/ai/conversation/delete', {
        body: JSON.stringify({ id }),
        method: 'POST',
      });
    },

    async rename(id: number, title: string): Promise<void> {
      await request<unknown>('/ai/conversation/rename', {
        body: JSON.stringify({ id, title }),
        method: 'POST',
      });
    },
  };
}

/**
 * 解析 CommonResult 信封（与共享协议层同一口径）。
 *
 * <p>为什么在本文件内联：本应用只依赖 `@vben/ai-embed-sdk`（不直接依赖 `@vben/ai-contracts`），
 * 错误类型复用 SDK 导出的 `AiChatApiError`（即共享协议层的稳定错误）。
 */
function parseEnvelope<T>(status: number, payload: unknown): T {
  const envelope =
    typeof payload === 'object' && payload !== null
      ? (payload as { code?: number; data?: T; msg?: string })
      : {};
  const code = typeof envelope.code === 'number' ? envelope.code : null;
  if (status < 200 || status >= 300) {
    throw new AiChatApiError(
      status,
      code === null || code === 0 ? `HTTP_${status}` : String(code),
      envelope.msg ?? `请求失败：HTTP ${status}`,
    );
  }
  if (code !== 0) {
    throw new AiChatApiError(
      status,
      code === null ? 'MALFORMED_RESPONSE' : String(code),
      envelope.msg ?? '响应缺少 CommonResult 信封',
    );
  }
  if (envelope.data === undefined) {
    throw new AiChatApiError(status, 'EMPTY_DATA', '响应缺少 data');
  }
  return envelope.data;
}

export { AiChatApiError };
