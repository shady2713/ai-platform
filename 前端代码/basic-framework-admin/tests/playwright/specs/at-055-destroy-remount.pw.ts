import type { CDPSession, Page } from '@playwright/test';

/**
 * AT-055：destroy 后重复 mount —— 无监听器/流/图表/DOM 残留（真实 Chromium + CDP 堆观察）。
 *
 * 方法（为什么要这样测）：
 *  1. **DOM 残留**：每轮 destroy 后统计宿主容器里的 overlay/panel/scroll/frame 数量；
 *  2. **监听器残留**：`addInitScript` 在页面脚本之前包装 `EventTarget.prototype` 的
 *     add/removeEventListener，按 target(window/document) 与类型计数，
 *     销毁后净计数必须回到 0（这正是"无监听器泄漏"的直接证据，不靠 DOM 推断）；
 *  3. **堆增长**：CDP `HeapProfiler.collectGarbage` + `Runtime.getHeapUsage` 取 V8 已用堆，
 *     先做一轮预热（iframe 首次加载会编译壳 bundle，代码常驻子堆，不能算作泄漏），
 *     再跑 N 轮 mount/open/destroy，比较 GC 后的净增长。
 *     **阈值留足余量**：实测值见运行日志，阈值取实测的若干倍并写明在断言里；
 *     这不是精确内存剖析（Chromium 专用，`Runtime.getHeapUsage` 是全 isolate 粒度）。
 *
 * iframe 内的流/图表随 frame 文档一起销毁：这里断言 frame 确实离开 frame 树
 * （`page.frames()` 不再包含壳页），跨帧对象由浏览器负责。
 * 也不依赖模型：全程没有对话调用（model-dependent: not configured in this environment）。
 */
import process from 'node:process';

import { expect, test } from '@playwright/test';

import { SHELL_A, shellUrl } from '../fixtures/origins.mjs';
import {
  capture,
  configureTicket,
  hostCall,
  openHostPage,
} from '../support/harness';

const APP = 'app-lifecycle';
const MOUNT_NAME = 'lifecycle';
const CYCLES = 10;
/** 净增长上限（字节）：实测约 1 MB/10 轮，这里留 6 倍余量。 */
const HEAP_GROWTH_LIMIT = 6 * 1024 * 1024;

const mountConfig = {
  allowedOrigins: [SHELL_A.origin],
  appCode: APP,
  frameUrl: shellUrl(SHELL_A.origin, {
    appCode: APP,
    instanceId: 'inst-lifecycle',
  }),
  instanceId: 'inst-lifecycle',
  mode: 'inline',
  name: MOUNT_NAME,
  targetOrigin: SHELL_A.origin,
};

interface ListenerCounts {
  document: Record<string, number>;
  window: Record<string, number>;
}

/** 净监听器计数（过滤掉加减抵消为 0 的键，便于直接比较）。 */
async function listenerCounts(page: Page): Promise<ListenerCounts> {
  return page.evaluate(() => {
    const raw = (globalThis as unknown as { __q06Listeners: ListenerCounts })
      .__q06Listeners;
    const active = (bag: Record<string, number>) =>
      Object.fromEntries(
        Object.entries(bag).filter(([, count]) => count !== 0),
      );
    return { document: active(raw.document), window: active(raw.window) };
  });
}

async function usedHeapSize(session: CDPSession): Promise<number> {
  await session.send('HeapProfiler.collectGarbage');
  const usage = await session.send('Runtime.getHeapUsage');
  return usage.usedSize;
}

test.describe('AT-055 destroy 与重复 mount', () => {
  test('十轮 mount/open/destroy：DOM 与监听器零残留，堆增长在阈值内', async ({
    page,
  }) => {
    // 监听器计数必须在页面脚本之前装好
    await page.addInitScript(() => {
      const counts: {
        document: Record<string, number>;
        window: Record<string, number>;
      } = { document: {}, window: {} };
      const originals = {
        add: EventTarget.prototype.addEventListener,
        remove: EventTarget.prototype.removeEventListener,
      };
      const bump = (target: EventTarget, type: string, delta: number) => {
        let key: 'document' | 'window' | null = null;
        if (target === document) key = 'document';
        else if (target === window) key = 'window';
        if (key === null) return;
        const bag = counts[key];
        bag[type] = (bag[type] ?? 0) + delta;
      };
      EventTarget.prototype.addEventListener = function patchedAdd(
        type: string,
        listener: EventListenerOrEventListenerObject,
        options?: AddEventListenerOptions | boolean,
      ) {
        bump(this, type, 1);
        return originals.add.call(this, type, listener, options);
      };
      EventTarget.prototype.removeEventListener = function patchedRemove(
        type: string,
        listener: EventListenerOrEventListenerObject,
        options?: boolean | EventListenerOptions,
      ) {
        bump(this, type, -1);
        return originals.remove.call(this, type, listener, options);
      };
      (
        globalThis as unknown as { __q06Listeners: typeof counts }
      ).__q06Listeners = counts;
    });

    await openHostPage(page);
    await configureTicket(page, { delayMs: 0 });
    const session = await page.context().newCDPSession(page);
    await session.send('HeapProfiler.enable');

    const domCounts = () =>
      page.evaluate(() => ({
        frames: document.querySelectorAll('[data-testid="ai-chat-frame"]')
          .length,
        iframes: document.querySelectorAll('iframe').length,
        overlays: document.querySelectorAll('[data-testid="ai-chat-overlay"]')
          .length,
        panels: document.querySelectorAll('[data-testid="ai-chat-panel"]')
          .length,
        scrollHosts: document.querySelectorAll('[data-testid="ai-chat-scroll"]')
          .length,
      }));

    const cycle = async () => {
      await hostCall(page, 'openMount', [mountConfig]);
      await hostCall(page, 'mountAction', [MOUNT_NAME, 'open']);
      await expect(page.locator('[data-testid="ai-chat-frame"]')).toHaveCount(
        1,
      );
      await hostCall(page, 'mountAction', [MOUNT_NAME, 'destroy']);
    };

    // 宿主页自身的监听器（如 message 路由）是基线，不属于挂载泄漏
    const baselineListeners = await listenerCounts(page);

    // 预热一轮：壳 bundle 的编译产物会留在 isolate 里，必须先计入基线
    await cycle();
    expect(await listenerCounts(page)).toEqual(baselineListeners);

    const baselineHeap = await usedHeapSize(session);
    const samples: number[] = [];
    for (let index = 0; index < CYCLES; index += 1) {
      await cycle();
      const dom = await domCounts();
      expect(dom, `第 ${index + 1} 轮 destroy 后不应有 DOM 残留`).toEqual({
        frames: 0,
        iframes: 0,
        overlays: 0,
        panels: 0,
        scrollHosts: 0,
      });
      // 每轮销毁后监听器净计数回到基线（挂载新增的 keydown/resize 都解绑了）
      expect(
        await listenerCounts(page),
        `第 ${index + 1} 轮 destroy 后监听器应回到基线`,
      ).toEqual(baselineListeners);
      samples.push(await usedHeapSize(session));
    }

    const finalHeap = samples.at(-1) ?? baselineHeap;
    const growth = finalHeap - baselineHeap;
    process.stdout.write(
      `[at-055] heap baseline=${baselineHeap} samples=${samples.join(',')} growth=${growth}\n`,
    );

    // frame 树里不再有壳页 frame：iframe 内的流/图表随文档销毁。
    // 注意排除主文档（宿主页自己的 URL 是 host-shell.html，也含 "shell.html" 子串）。
    const liveShellFrames = page
      .frames()
      .filter(
        (frame) =>
          frame !== page.mainFrame() && frame.url().includes('/shell.html?'),
      );
    expect(liveShellFrames).toHaveLength(0);

    // destroy 之后还能再挂载并再次销毁（幂等 + 可重复）
    await cycle();
    await hostCall(page, 'mountAction', [MOUNT_NAME, 'destroy']);
    expect(await domCounts()).toEqual({
      frames: 0,
      iframes: 0,
      overlays: 0,
      panels: 0,
      scrollHosts: 0,
    });
    expect(await listenerCounts(page)).toEqual(baselineListeners);

    expect(growth).toBeLessThan(HEAP_GROWTH_LIMIT);

    await capture(page, 'at-055-destroy-remount');
  });

  test('destroy 幂等：重复 destroy 不产生新节点', async ({ page }) => {
    await openHostPage(page);
    await configureTicket(page, { delayMs: 0 });
    await hostCall(page, 'openMount', [mountConfig]);
    await hostCall(page, 'mountAction', [MOUNT_NAME, 'open']);
    await expect(page.locator('[data-testid="ai-chat-frame"]')).toHaveCount(1);

    // 销毁两次：第二次必须是空操作（不抛错、不产生新节点）
    await hostCall(page, 'mountAction', [MOUNT_NAME, 'destroy']);
    await hostCall(page, 'mountAction', [MOUNT_NAME, 'destroy']);
    const dom = await page.evaluate(() => ({
      iframes: document.querySelectorAll('iframe').length,
      overlays: document.querySelectorAll('[data-testid="ai-chat-overlay"]')
        .length,
    }));
    expect(dom).toEqual({ iframes: 0, overlays: 0 });

    await capture(page, 'at-055-destroy-idempotent');
  });

  // 已确认缺陷（不属于本切片允许路径，无法在此修复）：destroy() 之后再调用 open() 会走
  // ensureShell() 重建 overlay/panel/iframe，而内部桥已是 DESTROYED（start() 直接返回），
  // 于是留下"僵尸面板 + 死桥"：既不会再握手，也会随每次 open/close 累积 DOM。
  // 期望行为：销毁后的实例不可复用（open 空操作），或明确报错。
  test.fail(
    '已确认缺陷：destroy() 后复用同一实例 open() 不应重建外壳',
    async ({ page }) => {
      await openHostPage(page);
      await configureTicket(page, { delayMs: 0 });
      await hostCall(page, 'openMount', [mountConfig]);
      await hostCall(page, 'mountAction', [MOUNT_NAME, 'open']);
      await hostCall(page, 'mountAction', [MOUNT_NAME, 'destroy']);
      await hostCall(page, 'mountAction', [MOUNT_NAME, 'open']);
      const afterOpen = await page.evaluate(() => ({
        iframes: document.querySelectorAll('iframe').length,
        overlays: document.querySelectorAll('[data-testid="ai-chat-overlay"]')
          .length,
      }));
      expect(afterOpen).toEqual({ iframes: 0, overlays: 0 });
    },
  );
});
