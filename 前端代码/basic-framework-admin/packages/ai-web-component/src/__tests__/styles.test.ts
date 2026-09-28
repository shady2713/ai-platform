import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  COMPONENT_STYLES,
  deriveSurfaceStylesUrl,
  installStyles,
} from '../element/styles';

/**
 * 样式装载（X09）：优先 constructable stylesheet（严格 CSP 下可用），
 * 不支持时回退到 `<style>` 元素；两种路径都只写文本，不解析 HTML。
 */
describe('组件样式的装载路径（X09）', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('支持 adoptedStyleSheets 时：写入 constructable sheet，不产生 <style> 元素', () => {
    const host = document.createElement('div');
    document.body.append(host);
    const root = host.attachShadow({ mode: 'open' });

    expect(installStyles(root, COMPONENT_STYLES)).toBe('adopted');
    expect(root.adoptedStyleSheets).toHaveLength(1);
    expect(root.querySelectorAll('style')).toHaveLength(0);
    expect(root.adoptedStyleSheets[0]?.cssRules.length).toBeGreaterThan(0);
  });

  it('不可用该能力时：回退到 <style> 元素（内容为文本，不是 HTML 拼接）', () => {
    const host = document.createElement('div');
    document.body.append(host);
    const root = host.attachShadow({ mode: 'open' });
    const original = globalThis.CSSStyleSheet;
    vi.stubGlobal('CSSStyleSheet', undefined);
    try {
      expect(installStyles(root, COMPONENT_STYLES)).toBe('style-element');
      const style = root.querySelector('style');
      expect(style).not.toBeNull();
      expect(style?.textContent).toBe(COMPONENT_STYLES);
      expect(root.adoptedStyleSheets).toHaveLength(0);
    } finally {
      vi.stubGlobal('CSSStyleSheet', original);
    }
  });

  it('adoptedStyleSheets 只读（赋值抛错）时同样回退，不把样式丢掉', () => {
    const host = document.createElement('div');
    document.body.append(host);
    const root = host.attachShadow({ mode: 'open' });
    Object.defineProperty(root, 'adoptedStyleSheets', {
      configurable: true,
      get: () => [],
      set: () => {
        throw new TypeError('readonly');
      },
    });
    expect(installStyles(root, COMPONENT_STYLES)).toBe('style-element');
    expect(root.querySelector('style')?.textContent).toBe(COMPONENT_STYLES);
  });

  it('样式资源地址推导：只认版本化产物命名，源码导入不猜路径', () => {
    expect(
      deriveSurfaceStylesUrl(
        'https://host.example.com/sdk/ai-web-component-5.6.0.js',
      ),
    ).toBe('https://host.example.com/sdk/ai-web-component-5.6.0.css');
    expect(
      deriveSurfaceStylesUrl(
        'https://host.example.com/sdk/ai-web-component-5.6.0.js?v=1',
      ),
    ).toBe('https://host.example.com/sdk/ai-web-component-5.6.0.css');
    // 源码/dev/宿主打包后的模块地址都不猜（避免把宿主的样式表注入 Shadow DOM）
    expect(
      deriveSurfaceStylesUrl(
        'http://localhost:5181/src/element/ai-chat-element.ts',
      ),
    ).toBeUndefined();
    expect(
      deriveSurfaceStylesUrl('https://host.example.com/assets/index-abc123.js'),
    ).toBeUndefined();
  });
});
