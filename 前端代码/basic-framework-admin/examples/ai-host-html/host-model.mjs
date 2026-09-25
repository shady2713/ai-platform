/**
 * 宿主示例的纯逻辑（C10）：与浏览器入口分离，便于单测。
 *
 * 这里只放"宿主自己的决定"：登记哪些路由、如何组装 SDK 选项、切用户时清理什么。
 * 桥协议相关的行为一律由 SDK（`@vben/ai-embed-sdk`）提供，示例不重复实现。
 */

/** 宿主登记的业务路由（未登记的路由名会被 SDK 的校验器拒绝）。 */
export const SAMPLE_ROUTES = {
  'order.detail': { params: { id: 'string' } },
  'report.list': { params: { page: 'number' } },
};

/** 宿主允许的形态（与 SDK 的 `ChatDisplayMode` 一致）。 */
export const SAMPLE_MODES = ['dialog', 'drawer', 'inline'];

/**
 * 组装 SDK 挂载选项。
 *
 * - `allowedOrigins` 只放平台 Origin（来自配置，不来自 URL 参数）；
 * - iframe 指向平台的自托管嵌入入口（C05），宿主不自己拼资源路径；
 * - `getAccessToken` 只向**宿主自己的后端**要票据，浏览器里没有长期凭据。
 */
export function buildMountOptions({
  appCode,
  container,
  embedBasePath,
  fetchTicket,
  instanceId,
}) {
  return {
    allowedOrigins: [new URL(embedBasePath).origin],
    appCode,
    container,
    frame: {
      create() {
        const frame = document.createElement('iframe');
        frame.src = `${embedBasePath}/app-api/ai/v1/embed/${appCode}`;
        frame.setAttribute('referrerpolicy', 'no-referrer');
        return frame;
      },
      destroy() {},
    },
    getAccessToken: fetchTicket,
    instanceId,
    mode: 'inline',
    theme: { fontFamily: 'system-ui', primaryColor: '#1677ff', radius: 6 },
  };
}

/** 票据端点路径（宿主后端自己的端点，不是平台接口）。 */
export const TICKET_ENDPOINT = '/your-backend/ai-ticket';

/**
 * 取票据：只走宿主后端；失败时抛出（不返回假票据，让 SDK 的失败路径生效）。
 */
export function createTicketFetcher(fetchImpl, endpoint = TICKET_ENDPOINT) {
  return async function fetchTicket() {
    const response = await fetchImpl(endpoint, { method: 'POST' });
    if (!response.ok) {
      throw new Error(`ticket ${response.status}`);
    }
    return response.json();
  };
}

/** 切用户的宿主侧动作：销毁旧实例（SDK 内部会换代并丢弃旧消息）。 */
export function switchUserAction(mount) {
  mount.destroy();
  return '已销毁旧实例；重新挂载即为新用户的新会话（旧响应由 SDK 按代次丢弃）';
}
