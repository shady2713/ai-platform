/**
 * AT-017 慢消费者与断流恢复（真实浏览器：独立 Chat 生产产物 + 真实 SSE 流读取）。
 *
 * 判定对象是**界面在服务端"慢/断"时的行为**，三条硬结论：
 *  1. **有界且不重复渲染**：慢速下发大量重复/乱序回放帧（含心跳），界面按 seq 去重后
 *     每个事件只渲染一次，一次运行只追加到同一条助手消息（渲染量不随帧数增长）；
 *  2. **增量消费**：流还没结束时界面已经显示已到达的片段（不是"整包缓冲后一次性渲染"）；
 *  3. **断流不假成功**：服务端非终态关闭时界面保持"执行中"（可重连），连接被重置时给
 *     稳定失败与"重试"入口——任何时候都不得出现"已完成"。
 *
 * 服务端剧本来自 `support/q07-resilience-api.mjs`（同协议形状的桩，非平台实现；
 * 平台侧的有界队列/背压由后端切片与压测覆盖）。不依赖模型
 * （model-dependent: not configured in this environment）。
 */
import { expect, test } from '@playwright/test';

import {
  errorTexts,
  messageCount,
  messageTexts,
  openChatApp,
  pageText,
  PHASE_TEXT,
  phaseBadge,
  sendMessage,
} from '../support/q07-harness';
import { Q07_RUN_KEY, startQ07Api } from '../support/q07-resilience-api.mjs';

const ACCEPTED_NOTE = `已受理运行 ${Q07_RUN_KEY}（RUNNING）`;

test.describe('AT-017 慢消费者与断流恢复', () => {
  test('慢速重复回放：按 seq 去重、每个事件只渲染一次、流中途不假完成', async ({
    page,
  }) => {
    const api = await startQ07Api();
    // 108 帧、8ms 一帧（约 0.9s）：既覆盖"慢"，也让"流中途"的断言有稳定窗口
    api.state.events = 'duplicate-burst';
    api.state.burstRounds = 12;
    api.state.burstFrameDelayMs = 8;
    try {
      await openChatApp(page, api.origin);
      await sendMessage(page, '统计一下本月金额');

      // 增量消费：流仍在进行时，已到达的片段必须已经显示，且**没有**终态
      await expect
        .poll(() => messageTexts(page), {
          message: '等待第一个片段渲染（流未结束）',
        })
        .toContain('片段一');
      expect(await pageText(page)).not.toContain('终态');
      await expect(phaseBadge(page)).toHaveText(PHASE_TEXT.running);

      // 放行终态帧；断言最终渲染恰好一次/每个事件
      api.releaseTerminal();
      await expect
        .poll(() => messageTexts(page), {
          message: '等待终态事件渲染',
        })
        .toEqual([
          '统计一下本月金额',
          ACCEPTED_NOTE,
          '片段一',
          '片段二',
          '片段三',
          '终态',
        ]);
      await expect(phaseBadge(page)).toHaveText(PHASE_TEXT.succeeded);

      // 有界：整轮运行只产生 2 条消息（用户 + 助手），片段追加在同一条助手消息里
      expect(await messageCount(page)).toBe(2);

      // 不重复受理、不重复订阅：108 帧里只发 1 次受理、1 次事件订阅
      expect(
        api.state.requests.filter((item) =>
          item.path.endsWith('/ai/run/accept'),
        ),
      ).toHaveLength(1);
      const eventCalls = api.eventsRequests();
      expect(eventCalls).toHaveLength(1);
      expect(eventCalls[0]?.query).toBe('?runId=4001');
    } finally {
      await api.close();
    }
  });

  test('服务端非终态断流：界面保持"执行中"（可重连），不显示完成、不重执行', async ({
    page,
  }) => {
    const api = await startQ07Api();
    api.state.events = 'graceful-close';
    try {
      await openChatApp(page, api.origin);
      await sendMessage(page, '统计一下本月金额');

      await expect
        .poll(() => messageTexts(page))
        .toEqual(['统计一下本月金额', ACCEPTED_NOTE, '片段一', '片段二']);
      await expect(phaseBadge(page)).toHaveText(PHASE_TEXT.running);

      // 断流之后不得"自己变成功"，也不得凭空追加内容
      await page.waitForTimeout(750);
      await expect(phaseBadge(page)).toHaveText(PHASE_TEXT.running);
      const texts = await messageTexts(page);
      expect(texts).toEqual([
        '统计一下本月金额',
        ACCEPTED_NOTE,
        '片段一',
        '片段二',
      ]);
      expect(await errorTexts(page)).toEqual([]);
      expect(await pageText(page)).not.toContain(PHASE_TEXT.succeeded);
      expect(await pageText(page)).not.toContain('终态');

      // 断线不重执行：受理与订阅各只有一次（重连应由宿主按 afterSeq 发起，见 AT-014 用例）
      expect(
        api.state.requests.filter((item) =>
          item.path.endsWith('/ai/run/accept'),
        ),
      ).toHaveLength(1);
      expect(api.eventsRequests()).toHaveLength(1);
      test.info().annotations.push({
        description:
          '非终态断流时客户端返回 reason=closed（可重连）：界面停在"执行中"，不把断流当成功',
        type: 'at-017',
      });
    } finally {
      await api.close();
    }
  });

  test('连接被重置：给出可见的稳定失败与重试入口，不显示成功', async ({
    page,
  }) => {
    const api = await startQ07Api();
    api.state.events = 'abrupt-reset';
    try {
      await openChatApp(page, api.origin);
      await sendMessage(page, '统计一下本月金额');

      await expect(phaseBadge(page)).toHaveText(PHASE_TEXT.failed);
      const errors = await errorTexts(page);
      expect(errors).toHaveLength(1);
      expect(errors[0]?.trim().length ?? 0).toBeGreaterThan(0);
      expect(await pageText(page)).not.toContain(PHASE_TEXT.succeeded);
      await expect(
        page.locator('[data-testid="ai-conversation-retry"]'),
      ).toBeVisible();

      // 如实记录界面给出的原因（当前是浏览器原始网络报文，不是稳定原因码）
      test.info().annotations.push({
        description: `连接被重置时界面显示的原因：${JSON.stringify(errors[0])}`,
        type: 'at-017',
      });
    } finally {
      await api.close();
    }
  });
});
