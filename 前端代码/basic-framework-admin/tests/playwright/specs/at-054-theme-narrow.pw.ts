import type { Page } from '@playwright/test';

/**
 * AT-054：主题深浅色与窄屏（375x812）结构断言 + Tab 焦点顺序与归还。
 *
 * ADR 0046 明令**不做视觉回归**，所以这里全部是结构/令牌断言：
 *  - 宿主外壳（真实 `createChatMount`）：窄屏铺满、有界高度、主题令牌生效、
 *    更新主题不重建 iframe（会话与滚动位置保留的前提）、模态焦点移入/循环/Esc 归还；
 *  - Chat 生产产物页：375x812 下关键结构存在且 `scrollWidth <= clientWidth`（无横向滚动）；
 *  - 管理端生产产物页：浅色/深色令牌切换真实生效，两种模式下窄屏都无横向滚动。
 *
 * 未覆盖（见 README）：
 *  - 报表页（R07）需要登录态与后端数据 ⇒ 本环境无法验证；
 *  - Chat/管理端的"深色观感"：Chat 应用当前没有深色样式（只有管理端的令牌系统有 `.dark`），
 *    因此深色只断言"结构稳定 + 令牌切换生效"，不声称观感一致；
 *  - 模型相关步骤（真实对话渲染）不在本用例内（model-dependent: not configured in this environment）。
 */
import { expect, test } from '@playwright/test';

import { ADMIN, CHAT_APP, SHELL_A, shellUrl } from '../fixtures/origins.mjs';
import {
  capture,
  configureTicket,
  hostCall,
  openHostPage,
} from '../support/harness';

const APP = 'app-narrow';
const NARROW = { height: 812, width: 375 };
const WIDE = { height: 800, width: 1280 };

async function openDialogMount(
  page: Page,
  mode: 'dialog' | 'inline',
  maxHeight: number,
) {
  await hostCall(page, 'openMount', [
    {
      allowedOrigins: [SHELL_A.origin],
      appCode: APP,
      frameUrl: shellUrl(SHELL_A.origin, {
        appCode: APP,
        instanceId: `inst-${mode}`,
      }),
      instanceId: `inst-${mode}`,
      maxHeight,
      mode,
      name: mode,
      targetOrigin: SHELL_A.origin,
      theme: { fontFamily: 'system-ui', primaryColor: '#1677ff', radius: 6 },
    },
  ]);
}

/** 宿主页与面板的横向溢出：`scrollWidth <= clientWidth`。 */
async function overflowReport(page: Page) {
  return page.evaluate(() => {
    const root = document.documentElement;
    const panel = document.querySelector('[data-testid="ai-chat-panel"]');
    return {
      body: {
        clientWidth: document.body.clientWidth,
        scrollWidth: document.body.scrollWidth,
      },
      panel:
        panel === null
          ? null
          : { clientWidth: panel.clientWidth, scrollWidth: panel.scrollWidth },
      root: { clientWidth: root.clientWidth, scrollWidth: root.scrollWidth },
      viewportWidth: window.innerWidth,
    };
  });
}

test.describe('AT-054 主题与窄屏可用性', () => {
  test('窄屏 375x812：模态面板铺满、无横向溢出、焦点移入且 Esc 归还', async ({
    page,
  }) => {
    await page.setViewportSize(NARROW);
    await openHostPage(page);
    await configureTicket(page, { delayMs: 0 });
    await openDialogMount(page, 'dialog', 600);

    // 用按钮打开（宿主里真实入口），焦点应移入面板
    await page.click('#q06-open-dialog');
    const panel = page.locator('[data-testid="ai-chat-panel"]');
    await expect(panel).toBeVisible();
    await expect(panel).toHaveAttribute('data-narrow', 'true');
    await expect(panel).toHaveAttribute('role', 'dialog');
    await expect(panel).toHaveAttribute('aria-modal', 'true');

    const box = await panel.boundingBox();
    expect(box?.width).toBeCloseTo(NARROW.width, 0);
    expect(box?.height).toBeCloseTo(NARROW.height, 0);

    const overflow = await overflowReport(page);
    expect(overflow.root.scrollWidth).toBeLessThanOrEqual(
      overflow.root.clientWidth,
    );
    expect(overflow.body.scrollWidth).toBeLessThanOrEqual(
      overflow.body.clientWidth,
    );
    expect(overflow.panel?.scrollWidth).toBeLessThanOrEqual(
      overflow.panel?.clientWidth ?? 0,
    );

    // iframe 的尺寸由**宿主**给定（SDK 不注入样式表）：宿主给了 100% 后，
    // 内容 frame 与面板同宽——这是"窄屏 Chat 铺满视口"的实际含义
    const frameBox = await page
      .locator('[data-testid="ai-chat-frame"]')
      .boundingBox();
    test.info().annotations.push({
      description:
        'createChatMount 只产出外壳 DOM（内联 width/height 给面板），iframe 的 box 完全由宿主 CSS 决定；裸挂载下浏览器默认是 300x150。',
      type: 'finding',
    });
    expect(frameBox?.width).toBeCloseTo(NARROW.width, 0);

    // 焦点移入面板（面板没有可聚焦子元素时保持在面板本身）
    const focusedAfterOpen = await page.evaluate(() => {
      const panelEl = document.querySelector('[data-testid="ai-chat-panel"]');
      return {
        containsActive: panelEl?.contains(document.activeElement) ?? false,
        isPanel: panelEl === document.activeElement,
      };
    });
    expect(focusedAfterOpen.isPanel).toBe(true);

    // Tab / Shift+Tab 不得把焦点漏到宿主页面
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Shift+Tab');
    const focusInside = await page.evaluate(() => {
      const panelEl = document.querySelector('[data-testid="ai-chat-panel"]');
      return panelEl?.contains(document.activeElement) ?? false;
    });
    expect(focusInside).toBe(true);

    // Esc 关闭并把焦点还给打开按钮
    await page.keyboard.press('Escape');
    await expect(panel).toBeHidden();
    const activeId = await page.evaluate(
      () => document.activeElement?.id ?? null,
    );
    expect(activeId).toBe('q06-open-dialog');

    await capture(page, 'at-054-narrow-dialog-focus');
  });

  test('宽屏有界高度协商：面板高度 = min(宿主上限, 视口可用高度)', async ({
    page,
  }) => {
    await page.setViewportSize(WIDE);
    await openHostPage(page);
    await configureTicket(page, { delayMs: 0 });
    await openDialogMount(page, 'dialog', 600);
    await page.click('#q06-open-dialog');

    const panel = page.locator('[data-testid="ai-chat-panel"]');
    await expect(panel).toHaveAttribute('data-narrow', 'false');
    const box = await panel.boundingBox();
    expect(box?.height).toBe(600);
    expect(box?.width).toBe(960);
    const overflow = await overflowReport(page);
    expect(overflow.root.scrollWidth).toBeLessThanOrEqual(
      overflow.root.clientWidth,
    );

    await capture(page, 'at-054-wide-dialog-bounded-height');
  });

  test('主题令牌生效且更新主题不重建 iframe（会话保留前提）', async ({
    page,
  }) => {
    await page.setViewportSize(WIDE);
    await openHostPage(page);
    await configureTicket(page, { delayMs: 0 });
    await openDialogMount(page, 'inline', 600);
    await hostCall(page, 'mountAction', ['inline', 'open']);
    await expect(page.locator('[data-testid="ai-chat-frame"]')).toHaveCount(1);

    const before = await page.evaluate(() => {
      const frame = document.querySelector('[data-testid="ai-chat-frame"]');
      const panel = document.querySelector('[data-testid="ai-chat-panel"]');
      if (!(frame instanceof HTMLIFrameElement) || panel === null) return null;
      frame.dataset.q06Marker = 'kept';
      return {
        marker: frame.dataset.q06Marker,
        primary: getComputedStyle(panel)
          .getPropertyValue('--ai-primary-color')
          .trim(),
        radius: getComputedStyle(panel).getPropertyValue('--ai-radius').trim(),
        src: frame.src,
      };
    });
    expect(before?.marker).toBe('kept');
    expect(before?.primary).toBe('#1677ff');

    await hostCall(page, 'mountAction', [
      'inline',
      'updateTheme',
      { fontFamily: 'system-ui', primaryColor: '#7c3aed', radius: 12 },
    ]);

    const after = await page.evaluate(() => {
      const frame = document.querySelector('[data-testid="ai-chat-frame"]');
      const panel = document.querySelector('[data-testid="ai-chat-panel"]');
      if (!(frame instanceof HTMLIFrameElement) || panel === null) return null;
      return {
        marker: frame.dataset.q06Marker ?? null,
        primary: getComputedStyle(panel)
          .getPropertyValue('--ai-primary-color')
          .trim(),
        radius: getComputedStyle(panel).getPropertyValue('--ai-radius').trim(),
        src: frame.src,
      };
    });
    // 令牌随主题更新生效
    expect(after?.primary).toBe('#7c3aed');
    expect(after?.radius).toBe('12px');
    // 同一个 iframe 元素（没有重建）⇒ 会话/滚动位置不会被重置
    expect(after?.marker).toBe('kept');
    expect(after?.src).toBe(before?.src);

    await capture(page, 'at-054-theme-tokens');
  });

  test('Chat 生产产物页在窄屏 375x812 结构完整且无横向滚动', async ({
    page,
  }) => {
    // 本环境**没有后端**（model-dependent: not configured in this environment），
    // Chat 页没有票据，会话列表请求必然失败；失败必须是"可见的稳定提示"，
    // 不能是未处理的页面错误 + 空白列表（缺陷 7 的回归断言）。
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(error.message));
    await page.setViewportSize(NARROW);
    await page.goto(`${CHAT_APP.origin}/`, { waitUntil: 'load' });
    await expect(page.locator('h1')).toHaveText('AI 助手');
    const status = await page
      .locator('[data-testid="ai-chat-status"]')
      .textContent();
    // 未配置后端时状态必须是明确的提示，不是空白（这里配的是本地基址，面板就绪）
    expect(['就绪', '未配置 AI 服务地址']).toContain(status?.trim() ?? '');

    // 列表加载失败：界面给出稳定原因码的可见提示（不显示 HTML、不吞错）
    const failureNotice = page.locator(
      '[data-testid="ai-conversation-list-error"]',
    );
    await expect(failureNotice).toBeVisible();
    await expect(failureNotice).toContainText('会话列表加载失败');
    // 原因码稳定可断言：无后端为 NETWORK_UNREACHABLE；有后端但无票据时为 HTTP_401/业务码
    const failureText = (await failureNotice.textContent()) ?? '';
    expect(failureText).toMatch(
      /NETWORK_UNREACHABLE|HTTP_\d{3}|AI_[A-Z_]+|\d{6,}/u,
    );
    expect(failureText).not.toContain('<');
    // 没有未处理的页面错误
    expect(pageErrors).toEqual([]);

    const overflow = await overflowReport(page);
    expect(overflow.root.scrollWidth).toBeLessThanOrEqual(
      overflow.root.clientWidth,
    );
    expect(overflow.body.scrollWidth).toBeLessThanOrEqual(
      overflow.body.clientWidth,
    );

    // 深色偏好下结构不变（Chat 应用当前没有深色样式，只断言结构与非空渲染）
    await page.emulateMedia({ colorScheme: 'dark' });
    await expect(page.locator('h1')).toHaveText('AI 助手');
    const darkOverflow = await overflowReport(page);
    expect(darkOverflow.root.scrollWidth).toBeLessThanOrEqual(
      darkOverflow.root.clientWidth,
    );

    test.info().annotations.push({
      description: `chat 页无后端时的可见失败提示：${JSON.stringify(
        failureText.trim().replaceAll(/\s+/gu, ' '),
      )}；未处理页面错误 ${JSON.stringify(pageErrors)}。`,
      type: 'finding',
    });

    await capture(page, 'at-054-chat-narrow');
  });

  test('管理端登录页：深浅色令牌生效，窄屏两种模式都无横向滚动', async ({
    page,
  }) => {
    await page.setViewportSize(NARROW);
    await page.goto(`${ADMIN.origin}/#/auth/login`, { waitUntil: 'load' });
    await expect(page.locator('input[name="username"]')).toBeVisible();
    await expect(page.locator('input[name="password"]')).toBeVisible();

    const readTokens = () =>
      page.evaluate(() => {
        const root = getComputedStyle(document.documentElement);
        return {
          background: root.getPropertyValue('--background').trim(),
          foreground: root.getPropertyValue('--foreground').trim(),
          isDark: document.documentElement.classList.contains('dark'),
          overflow: {
            clientWidth: document.documentElement.clientWidth,
            scrollWidth: document.documentElement.scrollWidth,
          },
        };
      });

    // 仓库的默认主题模式是 dark（@vben-core/preferences 的 config.ts），
    // 因此这里不假设初始值，只断言"切换前后令牌真的不同"与两种模式都不横向溢出。
    const initial = await readTokens();
    expect(initial.overflow.scrollWidth).toBeLessThanOrEqual(
      initial.overflow.clientWidth,
    );

    // 产品自身的深浅色开关就是 html 上的 .dark（design-tokens/dark.css）
    const toggled = await page.evaluate(() => {
      const root = document.documentElement;
      root.classList.toggle('dark');
      const styles = getComputedStyle(root);
      return {
        background: styles.getPropertyValue('--background').trim(),
        foreground: styles.getPropertyValue('--foreground').trim(),
        isDark: root.classList.contains('dark'),
        overflow: {
          clientWidth: root.clientWidth,
          scrollWidth: root.scrollWidth,
        },
      };
    });
    expect(toggled.isDark).toBe(!initial.isDark);
    // 令牌真的换了值（设计令牌生效，不是只有类名）
    expect(toggled.background).not.toBe(initial.background);
    expect(toggled.foreground).not.toBe(initial.foreground);
    expect(toggled.overflow.scrollWidth).toBeLessThanOrEqual(
      toggled.overflow.clientWidth,
    );

    // 深浅色两种模式下登录表单都可用（键盘可见焦点顺序：username → password）
    await page.locator('input[name="username"]').focus();
    await page.keyboard.press('Tab');
    const focusedName = await page.evaluate(
      () => (document.activeElement as HTMLInputElement | null)?.name ?? null,
    );
    expect(focusedName).toBe('password');

    await capture(page, 'at-054-admin-login-dark-narrow');
  });
});
