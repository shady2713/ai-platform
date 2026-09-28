/**
 * 组件样式（X09）：全部写在 Shadow DOM 内，**不加载任何外部样式**（无 @import、无 url()、无字体脚本）。
 *
 * <p>样式边界是双向的：
 * <ul>
 *   <li>宿主的 CSS 选择器进不来（Shadow DOM 的封装），只可能通过**继承属性**（font/color/line-height/…）
 *       影响观感，所以面板显式重置这些属性（见 `.ai-web-component__panel`）；</li>
 *   <li>组件自己的类名也不污染宿主页面；主题只通过 CSS 自定义属性（`--ai-*`）注入。</li>
 * </ul>
 *
 * <p>写入方式优先用 constructable stylesheet（`adoptedStyleSheets`）：
 * 它在严格 CSP（`style-src` 不含 `unsafe-inline`）下同样可用；不支持时回退到 `<style>` 元素。
 */

export const COMPONENT_STYLES = `
:host {
  display: block;
  position: relative;
  contain: layout style;
}
:host([hidden]) {
  display: none;
}
.ai-web-component {
  /* 继承属性是宿主 CSS 唯一能渗进 Shadow DOM 的通道：这里显式重置 */
  display: block;
  font-family: var(--ai-font-family, system-ui, sans-serif);
  font-size: 14px;
  line-height: 1.5;
  color: #1f2329;
  visibility: visible;
  letter-spacing: normal;
  text-align: start;
  opacity: 1;
  pointer-events: auto;
}
.ai-web-component__panel {
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  min-width: 0;
  padding: 12px;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: var(--ai-radius, 6px);
  /* 面板内的一切都由组件自己排版：重置会被宿主继承的属性 */
  font: inherit;
  color: inherit;
  letter-spacing: normal;
  text-align: start;
  text-transform: none;
  white-space: normal;
  word-break: normal;
  outline: none;
}
.ai-web-component__panel:focus-visible {
  outline: 2px solid var(--ai-primary-color, #1677ff);
  outline-offset: 2px;
}
.ai-web-component__header {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}
.ai-web-component__title {
  font-weight: 600;
}
.ai-web-component__status {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  color: #646a73;
  white-space: nowrap;
}
.ai-web-component__status[data-tone='error'] {
  color: #d54941;
}
.ai-web-component__close {
  padding: 2px 8px;
  font: inherit;
  color: inherit;
  cursor: pointer;
  background: #f5f6f7;
  border: 1px solid #dcdfe6;
  border-radius: var(--ai-radius, 6px);
}
.ai-web-component__surface {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  margin-top: 8px;
  overflow: auto;
}
/* 模态形态：drawer 从右侧推入，dialog 居中；两者都自带遮罩与焦点入口 */
.ai-web-component[data-mode='drawer'],
.ai-web-component[data-mode='dialog'] {
  position: fixed;
  inset: 0;
  z-index: 2147483000;
  display: flex;
  background: rgb(0 0 0 / 35%);
}
.ai-web-component[data-mode='drawer'] {
  justify-content: flex-end;
}
.ai-web-component[data-mode='dialog'] {
  align-items: center;
  justify-content: center;
}
.ai-web-component[data-mode='drawer'] .ai-web-component__panel,
.ai-web-component[data-mode='dialog'] .ai-web-component__panel {
  width: min(560px, 100% - 32px);
  max-height: min(var(--ai-max-height, 100vh), 100vh - 32px);
  height: 100%;
  box-shadow: 0 8px 32px rgb(0 0 0 / 18%);
}
.ai-web-component[data-mode='dialog'] .ai-web-component__panel {
  height: auto;
}
.ai-web-component[data-narrow='true'][data-mode='drawer'] .ai-web-component__panel,
.ai-web-component[data-narrow='true'][data-mode='dialog'] .ai-web-component__panel {
  width: 100%;
  max-height: 100%;
  height: 100%;
  border-radius: 0;
}
.ai-web-component[data-open='false'] .ai-web-component__panel {
  visibility: hidden;
}
`;

/**
 * 安装样式：优先 constructable stylesheet（严格 CSP 下可用），不支持时回退到 `<style>` 元素。
 *
 * @returns 实际使用的写入方式（便于测试与诊断）
 */
export function installStyles(
  root: ShadowRoot,
  css: string,
): 'adopted' | 'style-element' {
  const sheet = constructableSheet(css);
  if (sheet !== null) {
    try {
      root.adoptedStyleSheets = [sheet];
      return 'adopted';
    } catch {
      // 某些实现里 adoptedStyleSheets 只读：退回 <style>
    }
  }
  const element = root.ownerDocument.createElement('style');
  // 只赋文本（不拼接 HTML 字符串），CSP 与注入面都不引入新入口
  element.textContent = css;
  root.append(element);
  return 'style-element';
}

function constructableSheet(css: string): CSSStyleSheet | null {
  const Sheet = globalThis.CSSStyleSheet;
  if (
    typeof Sheet !== 'function' ||
    typeof Sheet.prototype.replaceSync !== 'function' ||
    !('adoptedStyleSheets' in ShadowRoot.prototype)
  ) {
    return null;
  }
  const sheet = new Sheet();
  sheet.replaceSync(css);
  return sheet;
}

/** 样式资源链接的 testid（诊断与用例用）。 */
export const SURFACE_STYLE_LINK_TESTID = 'ai-chat-component-styles';

/** 宿主直接给出样式文本时使用的 `<style>` 元素 testid。 */
export const SURFACE_STYLE_TEXT_TESTID = 'ai-chat-component-style-text';

const VERSIONED_ARTIFACT = /\/ai-web-component-\d+\.\d+\.\d+\.js(?:\?.*)?$/u;

/**
 * 从模块地址推导同目录的样式资源（版本化产物：`ai-web-component-<version>.js` → `.css`）。
 *
 * <p>只对**本包的版本化产物命名**生效：源码导入（dev/打包进宿主）时不猜路径，
 * 由宿主显式给出 `styles-url`，避免把宿主的样式表错误地注入 Shadow DOM。
 */
export function deriveSurfaceStylesUrl(moduleUrl: string): string | undefined {
  return VERSIONED_ARTIFACT.test(moduleUrl)
    ? moduleUrl.replace(/\.js(\?.*)?$/u, '.css')
    : undefined;
}
