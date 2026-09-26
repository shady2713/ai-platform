/**
 * Q06 浏览器夹具的 Origin/端口/产物目录定义。
 *
 * 为什么要有多个 Origin：AT-051/052/056/067 的断言对象就是"跨源"本身——
 * 宿主页、嵌入壳、攻击页、管理端产物必须是**不同的真实 Origin**，
 * 否则 postMessage 的来源校验、frame-ancestors 与网络断言都没有意义。
 *
 * 端口固定而不是随机：Origin 精确匹配是应用允许域的语义（A01），
 * 随机端口会让"允许域"配置跟着变，夹具断言也就失去了确定性。
 */
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** `tests/playwright/fixtures` 目录。 */
export const FIXTURES_DIR = dirname(fileURLToPath(import.meta.url));
/** 前端工作区根目录（`前端代码/basic-framework-admin`）。 */
export const FRONTEND_ROOT = resolve(FIXTURES_DIR, '../../..');
/** 浏览器产物目录（已被 .gitignore 忽略，不提交任何二进制/录像）。 */
export const ARTIFACT_ROOT = resolve(
  FRONTEND_ROOT,
  '../../.local-state/q06-browser',
);

/** SDK 版本化产物目录（C10 契约：`ai-embed-sdk-<version>.js`）。 */
export const SDK_DIST_DIR = resolve(
  FRONTEND_ROOT,
  'packages/ai-embed-sdk/dist',
);
/** 独立 Chat 应用产物目录。 */
export const CHAT_APP_DIST_DIR = resolve(FRONTEND_ROOT, 'apps/ai-chat/dist');
/** 管理端产物目录。 */
export const ADMIN_DIST_DIR = resolve(FRONTEND_ROOT, 'apps/web-ele/dist');
/** 夹具自建产物（嵌入壳 bundle）目录。 */
export const SHELL_BUNDLE_DIR = resolve(ARTIFACT_ROOT, 'artifacts/shell');

function origin(port) {
  return `http://127.0.0.1:${port}`;
}

/** 宿主页 Origin：模拟第三方宿主的页面。 */
export const HOST = { origin: origin(5290), port: 5290 };
/** 嵌入壳 Origin A：应用发布配置允许的 Origin（真实 IframeBridge 夹具）。 */
export const SHELL_A = { origin: origin(5291), port: 5291 };
/** 攻击页 Origin：未在任何允许域里的第三方页面。 */
export const ATTACK = { origin: origin(5292), port: 5292 };
/** 嵌入壳 Origin B：真实嵌入壳，但**不在**允许域里（用于"错误 origin"用例）。 */
export const SHELL_B = { origin: origin(5293), port: 5293 };
/** 管理端产物 Origin（web-ele 生产构建）。 */
export const ADMIN = { origin: origin(5294), port: 5294 };
/** 独立 Chat 产物 Origin（ai-chat 生产构建）。 */
export const CHAT_APP = { origin: origin(5295), port: 5295 };

/** 夹具换票端点：宿主自己的后端（对应 C10 示例的 `/your-backend/ai-ticket`）。 */
export const TICKET_PATH = '/your-backend/ai-ticket';
export const TICKET_ENDPOINT = `${HOST.origin}${TICKET_PATH}`;

/** 宿主页路径。 */
export const HOST_PAGE_PATH = '/host-shell.html';
export const ATTACK_PAGE_PATH = '/attack.html';
export const SHELL_PAGE_PATH = '/shell.html';

/** 平台基址（真实后端；本机默认 48080，可用 Q06_PLATFORM_BASE 覆盖）。 */
export const PLATFORM_BASE =
  process.env.Q06_PLATFORM_BASE ?? 'http://127.0.0.1:48080';

/**
 * 组装嵌入壳的 iframe URL。
 *
 * `instance` 与 `app` 走 URL 查询串（实例标识是非秘密参数，C06 的约定），
 * 允许域一律由壳自己的 bootstrap 端点给出，**不接受**查询串里的允许域。
 */
export function shellUrl(shellOrigin, { appCode, instanceId }) {
  const url = new URL(`${shellOrigin}${SHELL_PAGE_PATH}`);
  url.searchParams.set('app', appCode);
  url.searchParams.set('instance', instanceId);
  return url.href;
}
