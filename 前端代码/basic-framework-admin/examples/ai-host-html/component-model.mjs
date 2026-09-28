/**
 * 组件路径的宿主侧纯逻辑（X09）：与浏览器入口分离，便于单测。
 *
 * 只放"宿主自己的决定"：用哪个版本化产物、给元素什么参数、切用户做什么。
 * 换票、路由登记表、事件校验都复用 iframe 路径同一份实现（`host-model.mjs`）。
 */

/** 版本化组件产物（与 iframe SDK 同一口径：固定命名、自托管、由示例后端提供）。 */
export const COMPONENT_ARTIFACT_PATH = '/component/ai-web-component-5.6.0.js';

/** 组件路径的应用端基址：**宿主自己的网关**（同源转发到平台应用端）。 */
export const COMPONENT_GATEWAY_BASE = '/your-backend/app-api';

/** 主题预设（只允许品牌主色与半径，字体不可覆盖，见 C04）。 */
export const COMPONENT_THEME_PRESETS = {
  dark: {
    fontFamily: 'system-ui',
    primaryColor: '#7c3aed',
    radius: 8,
  },
  light: {
    fontFamily: 'system-ui',
    primaryColor: '#1677ff',
    radius: 6,
  },
};

/**
 * 组装元素的宿主参数。
 *
 * - `api-base-url` 指向宿主自己的网关（同源）：浏览器不跨源、也不需要平台开 CORS；
 * - `allow-origins` 留空（同源基址无需登记）；跨源部署时在这里登记平台 Origin；
 * - `routes` 与 iframe 路径是同一张登记表（未登记路由会被元素按登记表拒绝）；
 * - 换票回调与 iframe 路径同一个签名，指向宿主自己的后端。
 */
export function buildComponentAttributes({
  appCode,
  gatewayBase = COMPONENT_GATEWAY_BASE,
  instanceId,
  routes,
  serviceId,
  theme = COMPONENT_THEME_PRESETS.light,
}) {
  return {
    'api-base-url': gatewayBase,
    'app-code': appCode,
    'instance-id': instanceId,
    ...(serviceId === undefined ? {} : { 'service-id': serviceId }),
    routes: JSON.stringify(routes),
    theme: JSON.stringify(theme),
  };
}

/** 切用户的宿主侧动作：换代并重建界面，晚到的旧响应由桥按代次丢弃。 */
export function switchUserAction(element) {
  element.resetSession();
  return '已切用户：换代并重建界面（旧代次的响应被丢弃），上下文已清空';
}
