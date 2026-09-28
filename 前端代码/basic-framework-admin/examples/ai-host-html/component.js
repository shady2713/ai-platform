/**
 * 组件路径的浏览器入口（X09）：加载**版本化组件产物**，把宿主 UI 接到元素的 API 与事件上。
 *
 * 与 iframe 路径（`host.js`）的差别只有"承载方式"：宿主不再建 iframe，元素直接把 ChatUI 挂进
 * Shadow DOM；换票回调、路由登记表、事件校验与主题 tokens 都是同一套（复用 `host-model.mjs`）。
 */
import {
  buildComponentAttributes,
  COMPONENT_ARTIFACT_PATH,
  COMPONENT_THEME_PRESETS,
  switchUserAction,
} from './component-model.mjs';
import { createTicketFetcher, SAMPLE_ROUTES } from './host-model.mjs';

export { COMPONENT_ARTIFACT_PATH, SAMPLE_ROUTES };

export async function mountComponentSampleHost({
  appCode,
  container,
  gatewayBase,
  serviceId,
  status,
}) {
  // 按固定版本路径加载产物（自托管，无公共 CDN），再注册自定义元素
  const artifacts = await import(COMPONENT_ARTIFACT_PATH);
  artifacts.defineAiChatElement();

  const element = document.createElement('ai-chat-component');
  const attributes = buildComponentAttributes({
    appCode,
    gatewayBase,
    instanceId: `host-component-${Math.random().toString(36).slice(2, 10)}`,
    routes: SAMPLE_ROUTES,
    serviceId,
  });
  for (const [name, value] of Object.entries(attributes)) {
    element.setAttribute(name, value);
  }
  // 换票回调与 iframe 路径共用同一实现：只向宿主自己的后端要票
  element.getAccessToken = createTicketFetcher(fetch.bind(globalThis));

  element.addEventListener('ai-ready', () => {
    status.textContent = '组件已连接（票据经宿主后端换取，票据只在组件内存里）';
  });
  element.addEventListener('ai-error', (event) => {
    status.textContent = `组件错误：${event.detail.errorCode}（${event.detail.message}）`;
  });
  element.addEventListener('ai-navigate-request', (event) => {
    status.textContent = `导航请求：${event.detail.route} ${JSON.stringify(event.detail.params)}（由宿主决定是否跳转）`;
  });
  element.addEventListener('ai-rejected', (event) => {
    status.textContent = `请求被拒绝：${event.detail.reason}`;
  });
  element.addEventListener('ai-report-created', (event) => {
    status.textContent = `报表已创建：${event.detail.reportId}`;
  });

  container.append(element);
  element.open();
  status.textContent = '已挂载组件（等待握手；票据由本示例后端换取）';

  const actions = {
    close: () => element.close(),
    context: () =>
      element.updateContext({ objectId: 'order-1', page: 'crm/order' }),
    destroy: () => {
      element.destroy();
      status.textContent = '已销毁（监听器、桥与界面一并清理）';
    },
    dialog: () => element.setMode('dialog'),
    drawer: () => element.setMode('drawer'),
    inline: () => element.setMode('inline'),
    navigate: (request) =>
      element.requestNavigate(request.route, request.params),
    open: () => element.open(),
    report: () =>
      element.notifyReportCreated({ reportId: 'rpt_sample_1', version: 1 }),
    switchUser: () => {
      status.textContent = switchUserAction(element);
    },
    themeDark: () => element.updateTheme(COMPONENT_THEME_PRESETS.dark),
    themeLight: () => element.updateTheme(COMPONENT_THEME_PRESETS.light),
  };

  // 把按钮接到动作上（示例演示用；真实宿主按自己的框架组织）
  for (const button of document.querySelectorAll('[data-component-action]')) {
    const name = button.dataset.componentAction ?? '';
    const key = name.startsWith('theme-')
      ? `theme${name.slice('theme-'.length).replace(/^./u, (char) => char.toUpperCase())}`
      : name;
    button.addEventListener('click', () => actions[key]?.());
  }

  return { actions, element };
}
