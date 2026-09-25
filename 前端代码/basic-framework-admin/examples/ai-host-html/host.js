import {
  buildMountOptions,
  createTicketFetcher,
  SAMPLE_ROUTES,
  switchUserAction,
} from './host-model.mjs';

/**
 * 纯 HTML 宿主入口（C10）：加载**版本化 SDK 产物**，把宿主 UI 接到 SDK 的生命周期与事件上。
 *
 * 依赖 `host-model.mjs` 的纯逻辑与本示例后端（`server.mjs`）的换票端点。
 */
import {
  createBusinessContextStore,
  createChatMount,
  createHostEventHandlers,
} from '/sdk/ai-embed-sdk-5.6.0.js';

export { SAMPLE_ROUTES };

export async function mountSampleHost({
  appCode,
  container,
  embedBasePath,
  status,
}) {
  const contexts = createBusinessContextStore({
    objectId: 'order-1',
    page: 'crm/order',
  });
  const events = createHostEventHandlers({
    onNavigate: ({ route, params }) => {
      status.textContent = `导航请求：${route} ${JSON.stringify(params)}（由宿主决定是否跳转）`;
    },
    onReportCreated: ({ reportId }) => {
      status.textContent = `报表已创建：${reportId}`;
    },
    routes: SAMPLE_ROUTES,
  });

  const mount = createChatMount(
    buildMountOptions({
      appCode,
      container,
      embedBasePath,
      fetchTicket: createTicketFetcher(fetch.bind(globalThis)),
      instanceId: `host-${Math.random().toString(36).slice(2, 10)}`,
    }),
  );

  mount.open();
  status.textContent = '已打开（等待嵌入页握手；票据由本示例后端换取）';

  const actions = {
    close: () => mount.close(),
    context: () => {
      const next =
        contexts.current()?.objectId === 'order-1' ? 'order-2' : 'order-1';
      contexts.update({ objectId: next, page: 'crm/order' });
      status.textContent = `已切换上下文到 ${next}（只作用于下一次运行）`;
      return next;
    },
    destroy: () => {
      mount.destroy();
      status.textContent = '已销毁（监听器/iframe/令牌一并清理）';
    },
    dialog: () => mount.setMode('dialog'),
    drawer: () => mount.setMode('drawer'),
    inline: () => mount.setMode('inline'),
    open: () => mount.open(),
    route: (request) => events.navigate(request),
    switchUser: () => {
      status.textContent = switchUserAction(mount);
    },
    themeDark: () =>
      mount.updateTheme({
        fontFamily: 'system-ui',
        primaryColor: '#7c3aed',
        radius: 8,
      }),
    themeLight: () =>
      mount.updateTheme({
        fontFamily: 'system-ui',
        primaryColor: '#1677ff',
        radius: 6,
      }),
  };

  // 把按钮接到动作上（示例演示用；真实宿主按自己的框架组织）
  for (const button of document.querySelectorAll('[data-action]')) {
    const name = button.dataset.action ?? '';
    const action = name.replaceAll('-', '').replace('theme', 'theme');
    const key = name.startsWith('theme-')
      ? `theme${name.slice('theme-'.length).replace(/^./u, (char) => char.toUpperCase())}`
      : action;
    button.addEventListener('click', () => actions[key]?.());
  }

  return { actions, contexts, events, mount };
}
