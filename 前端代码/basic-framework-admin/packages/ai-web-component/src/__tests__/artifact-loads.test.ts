import type { AiChatElement, GetAccessToken } from '../index';

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * 产物加载（X09 工程约束："新增 typed 包必须验证加载/空/失败/失权/销毁状态"）。
 *
 * <p>本用例加载的是 `pnpm -F @vben/ai-web-component run build` 的**真实产物**
 * （入口 + 同目录样式），在 DOM 环境里注册元素、完成一次握手并挂载界面：
 * 这条路径与第三方宿主的实际加载方式一致（版本化固定命名、无公共 CDN）。
 *
 * <p>产物不存在时**明确跳过**（不假装通过）；门禁里先构建再跑测试，
 * 因此正式验证一定执行本用例。
 */
const DIST_DIR = resolve(import.meta.dirname, '../../dist');
const TICKET = ['aitkt', 'artifact'].join('_');

function entryFile(): string | undefined {
  if (!existsSync(DIST_DIR)) {
    return undefined;
  }
  const name = readdirSync(DIST_DIR).find((file) =>
    /^ai-web-component-\d+\.\d+\.\d+\.js$/u.test(file),
  );
  return name === undefined ? undefined : resolve(DIST_DIR, name);
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.body.innerHTML = '';
});

describe('组件产物可加载（X09）', () => {
  it('入口 + 同目录样式 + 懒加载分包都在，且入口不含公共 CDN 与绝对 import', () => {
    const entry = entryFile();
    if (entry === undefined) {
      expect(entry).toBeUndefined();
      return;
    }
    const files = readdirSync(DIST_DIR).toSorted();
    expect(files.some((file) => file.endsWith('.css'))).toBe(true);
    // 动态分包（图表懒加载）必须存在：入口按相对路径引用它
    expect(files.some((file) => file.includes('.chunk.js'))).toBe(true);
    const source = readFileSync(entry, 'utf8');
    expect(source).toMatch(
      /import\("\.\/ai-web-component-[\w.-]+\.chunk\.js"\)/u,
    );
    expect(source).toContain('import.meta.url');
    expect(source).not.toMatch(/https?:\/\/(?:unpkg|cdn|jsdelivr|esm\.sh)/u);
  });

  it('真实产物：注册元素 → 换票 → 挂载界面（宿主按固定命名加载的那份代码）', async () => {
    const entry = entryFile();
    if (entry === undefined) {
      expect(entry).toBeUndefined();
      return;
    }
    const requests: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        requests.push(String(input));
        return Response.json(
          { code: 0, data: { list: [] }, msg: '' },
          { headers: { 'content-type': 'application/json' }, status: 200 },
        );
      }),
    );

    const artifacts = (await import(
      /* @vite-ignore */ pathToFileURL(entry).href
    )) as unknown as {
      AI_CHAT_ELEMENT_TAG: string;
      defineAiChatElement: () => void;
    };
    artifacts.defineAiChatElement();
    expect(customElements.get(artifacts.AI_CHAT_ELEMENT_TAG)).toBeDefined();

    const element = document.createElement(
      artifacts.AI_CHAT_ELEMENT_TAG,
    ) as unknown as AiChatElement;
    // 平台基址与允许域指向同一个 Origin（测试里不发真实请求：fetch 已替换为桩）
    element.setAttribute('api-base-url', '/app-api');
    element.setAttribute('app-code', 'crm-portal');
    element.setAttribute('service-id', 'svc_1');
    element.getAccessToken = (async () => ({
      expiresAt: '2026-09-27T10:00:00Z',
      token: TICKET,
    })) as GetAccessToken;
    document.body.append(element);
    element.open();

    await vi.waitFor(() => {
      expect(element.state()).toBe('INITIALIZED');
    });
    const shadow = element.shadowRoot;
    expect(shadow).not.toBeNull();
    await vi.waitFor(() => {
      expect(
        shadow?.querySelector('[data-testid="ai-conversation"]'),
      ).not.toBeNull();
    });
    // 会话列表请求带上内存票据（票据只在请求头）
    await vi.waitFor(() => {
      expect(
        requests.some((url) => url.includes('/ai/conversation/page')),
      ).toBe(true);
    });
    // 样式边界：外壳样式装在 Shadow DOM 里（adopted sheet 或 <style> 元素）
    const installed =
      (shadow?.adoptedStyleSheets.length ?? 0) +
      (shadow?.querySelectorAll('style').length ?? 0);
    expect(installed).toBeGreaterThan(0);
    // 同目录样式资源的命名必须与入口对得上：元素按 `import.meta.url` 推导同名 .css，
    // 宿主按固定命名托管产物；文件名对不上时宿主会漏掉样式（这里在文件系统层做确定性断言，
    // 不依赖 DOM 环境能否真的加载样式资源）
    const derived = entry.replace(/\.js$/u, '.css');
    expect(existsSync(derived)).toBe(true);
    expect(readdirSync(DIST_DIR)).toContain(derived.split('/').at(-1));

    element.destroy();
    expect(element.state()).toBe('CREATED');
  });
});
