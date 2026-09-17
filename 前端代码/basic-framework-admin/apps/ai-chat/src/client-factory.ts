import type { AiChatClient } from '@vben/ai-embed-sdk';

import { createAiChatClient } from '@vben/ai-embed-sdk';

/** 票据读取接口：只要求 getItem，便于在不依赖浏览器存储的实现中测试。 */
export interface TicketStorage {
  getItem(key: string): null | string;
}

export interface BuildClientOptions {
  baseUrl?: string | undefined;
  ticketStorage?: Pick<Storage, 'getItem'> | TicketStorage | undefined;
}

/**
 * 按运行环境装配开放 API 客户端。
 *
 * - 未配置基址时返回 null，由会话逻辑给出明确错误块，而不是向未配置的地址发请求；
 * - 票据只在调用时从宿主存储读取，SDK 不持有凭据；
 * - 独立 Chat 只访问开放 API，不读取管理端运行时配置。
 */
export function buildAiChatClient(
  options: BuildClientOptions,
): AiChatClient | null {
  const baseUrl = options.baseUrl?.trim();
  if (!baseUrl) {
    return null;
  }
  return createAiChatClient({
    accessToken: () =>
      options.ticketStorage?.getItem('ai_access_ticket') ?? null,
    baseUrl,
  });
}
