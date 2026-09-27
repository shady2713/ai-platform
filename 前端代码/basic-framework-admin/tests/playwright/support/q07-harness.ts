import type { Locator, Page } from '@playwright/test';

import { expect } from '@playwright/test';

import { CHAT_APP, PLATFORM_BASE } from '../fixtures/origins.mjs';

/**
 * Q07 韧性用例共用夹具操作：打开真实 Chat 产物页（接口重定向到本地桩）、发送、读界面文本。
 *
 * 断言全部取自**页面上的可见文本**（`data-testid` 契约来自 ConversationPanel），
 * 不在 Node 侧模拟界面状态——这是"真实浏览器"的含义。
 */

/** 阶段徽标文案（与 `ConversationPanel.vue` 的 phaseText 一致）。 */
export const PHASE_TEXT = {
  failed: '失败（可重试）',
  idle: '等待输入',
  running: '执行中',
  succeeded: '已完成',
  waiting: '等待确认',
} as const;

/**
 * 打开独立 Chat 的生产产物页，并把平台的接口请求**重定向**到本地桩。
 *
 * 为什么要重定向：产物里的基址是编译期常量（`VITE_AI_API_BASE_URL`），本机没有后端；
 * 用例要构造"429 / 慢速 SSE / 断流"这些服务端剧本，只能用同协议形状的桩回应。
 * 重定向只改 Origin，路径与查询串原样保留，因此**客户端行为与真实部署一致**。
 */
export async function openChatApp(
  page: Page,
  apiOrigin: string,
): Promise<void> {
  await page.route(`${PLATFORM_BASE}/**`, async (route) => {
    await route.continue({
      url: route.request().url().replace(PLATFORM_BASE, apiOrigin),
    });
  });
  await page.goto(`${CHAT_APP.origin}/`, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('[data-testid="ai-conversation"]')).toBeVisible();
  await expect(page.locator('[data-testid="ai-chat-status"]')).toHaveText(
    '就绪',
  );
}

export async function sendMessage(page: Page, text: string): Promise<void> {
  await page.locator('[data-testid="ai-conversation-input"]').fill(text);
  await page.locator('[data-testid="ai-conversation-send"]').click();
}

/** 阶段徽标。 */
export function phaseBadge(page: Page): Locator {
  return page.locator('[data-testid="ai-conversation-phase"]');
}

/** 全部消息文本块（用户与助手消息按渲染顺序）。 */
export function messageTexts(page: Page): Promise<string[]> {
  return page.locator('[data-testid="ai-conversation-text"]').allInnerTexts();
}

/** 全部错误块文本。 */
export function errorTexts(page: Page): Promise<string[]> {
  return page.locator('[data-testid="ai-conversation-error"]').allInnerTexts();
}

/** 助手消息条数（用于"渲染有界：一次运行只追加到同一条消息"）。 */
export function messageCount(page: Page): Promise<number> {
  return page.locator('[data-testid="ai-conversation-message"]').count();
}

/** 页面可见正文（断言"不得出现某个词"时用，覆盖整页而不只是某个 testid）。 */
export function pageText(page: Page): Promise<string> {
  return page.locator('body').innerText();
}
