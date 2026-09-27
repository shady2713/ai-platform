/**
 * AT-059 并发配额/限流拒绝在界面上的可解释性（真实浏览器 + 真实 Chat 生产产物）。
 *
 * 判定对象（前端切片）：被拒时界面**如实显示服务端原因**、不伪装成功、不永久卡死：
 *  - 受理被 429（`AI_QUOTA_EXCEEDED` = 1_003_001_005）拒绝：界面显示服务端 msg（含占位信息）、
 *    阶段进入"失败（可重试）"、不出现"已受理运行"；
 *  - 事件订阅被 429 拒绝：已受理的提示保留，但**不得**显示"已完成"，失败原因可见；
 *  - 配额恢复后点"重试"能重新受理（可恢复，不是永久占位）。
 *
 * 服务端剧本来自 `support/q07-resilience-api.mjs`（同协议形状的桩）。配额的真实计数/释放
 * （不超发、不永久占位）在 Redis/MySQL 侧，属后端切片；本用例只判界面可解释性。
 */
import { expect, test } from '@playwright/test';

import {
  errorTexts,
  messageTexts,
  openChatApp,
  pageText,
  PHASE_TEXT,
  phaseBadge,
  sendMessage,
} from '../support/q07-harness';
import { startQ07Api } from '../support/q07-resilience-api.mjs';

const QUOTA_MSG = '并发配额已用尽（占位 8/8），请稍后重试';

test.describe('AT-059 配额拒绝的可解释性', () => {
  test('受理被 429：显示服务端原因、不假成功，配额恢复后可重试受理', async ({
    page,
  }) => {
    const api = await startQ07Api();
    api.state.accept = 'quota';
    try {
      await openChatApp(page, api.origin);
      await sendMessage(page, '统计一下本月金额');

      await expect(phaseBadge(page)).toHaveText(PHASE_TEXT.failed);
      await expect
        .poll(() => errorTexts(page), { message: '等待失败原因渲染' })
        .toHaveLength(1);
      const errors = await errorTexts(page);
      // 原样显示服务端的稳定原因（含占位信息），不换成"假成功"或空白
      expect(errors[0]).toBe(QUOTA_MSG);
      // 不伪装成功：没有"已受理运行"，也没有"已完成"
      expect(await messageTexts(page)).toEqual(['统计一下本月金额']);
      expect(await pageText(page)).not.toContain('已受理运行');
      expect(await pageText(page)).not.toContain(PHASE_TEXT.succeeded);

      const firstAccept = api.state.requests.find((item) =>
        item.path.endsWith('/ai/run/accept'),
      );
      expect(firstAccept?.method).toBe('POST');

      // 配额恢复：重试必须能重新受理（界面可恢复，不是永久卡死）
      api.state.accept = 'ok';
      api.state.events = 'graceful-close';
      await page.locator('[data-testid="ai-conversation-retry"]').click();
      await expect
        .poll(() => messageTexts(page), {
          message: '配额恢复后重试应重新受理',
        })
        .toContain('已受理运行 run_4001（RUNNING）');
      expect(
        api.state.requests.filter((item) =>
          item.path.endsWith('/ai/run/accept'),
        ),
      ).toHaveLength(2);
      test.info().annotations.push({
        description: `受理被拒的两次请求幂等键：${JSON.stringify(
          api.state.requests
            .filter((item) => item.path.endsWith('/ai/run/accept'))
            .map((item) => item.idempotencyKey),
        )}（C02 证据称"重试复用同一幂等键"，实现每次生成新键，见 Q07 报告"观察项"）`,
        type: 'at-059',
      });
    } finally {
      await api.close();
    }
  });

  test('事件订阅被 429：已受理但不显示完成，失败原因可见', async ({ page }) => {
    const api = await startQ07Api();
    api.state.events = 'quota';
    try {
      await openChatApp(page, api.origin);
      await sendMessage(page, '统计一下本月金额');

      await expect
        .poll(() => messageTexts(page))
        .toContain('已受理运行 run_4001（RUNNING）');
      await expect(phaseBadge(page)).toHaveText(PHASE_TEXT.failed);
      await expect
        .poll(() => errorTexts(page), { message: '等待订阅失败原因渲染' })
        .toHaveLength(1);
      const errors = await errorTexts(page);
      expect(errors[0]).toBe(QUOTA_MSG);
      expect(await pageText(page)).not.toContain(PHASE_TEXT.succeeded);
      expect(await pageText(page)).not.toContain('终态');
      await expect(
        page.locator('[data-testid="ai-conversation-retry"]'),
      ).toBeVisible();
    } finally {
      await api.close();
    }
  });
});
