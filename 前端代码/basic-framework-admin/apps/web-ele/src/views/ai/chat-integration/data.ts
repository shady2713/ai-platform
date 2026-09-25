/**
 * Chat 集成页纯逻辑（C09）：接入代码生成、能力版本与帮助文本。
 *
 * <p>三条硬规则（对应卡片验收）：
 * <ol>
 *   <li><b>复制出去的代码不含任何真实票据</b>：契约里 token 一律是占位符 `&lt;TICKET&gt;`，
 *       由宿主**自己的后端**用应用客户端凭据换取（浏览器里不放长期凭据）；</li>
 *   <li><b>不做第二套协议</b>：代码里的协议版本、桥消息与 SDK 版本都取自平台常量；</li>
 *   <li><b>不输出脚本可执行内容</b>：生成的是纯文本接入片段，任何字段都只做插值，不做 HTML 拼接。</li>
 * </ol>
 */

export type ChatDisplayMode = 'dialog' | 'drawer' | 'inline';

/** 集成模式选项（与设计契约 7.1 的 mode 取值一致） */
export const MODE_OPTIONS: { label: string; value: ChatDisplayMode }[] = [
  { label: '内嵌区块（inline）', value: 'inline' },
  { label: '侧栏抽屉（drawer）', value: 'drawer' },
  { label: '居中弹窗（dialog）', value: 'dialog' },
];

/** 能力与版本清单：页面展示用，取值来自平台契约常量（不写死第二份） */
export const CAPABILITIES: { detail: string; name: string }[] = [
  {
    detail: '桥协议消息白名单与版本协商（HELLO/READY/AUTH/INIT/…）',
    name: '嵌入桥协议 v1.0',
  },
  {
    detail: '票据在内存、只走 AUTH 消息；宿主后端换票，浏览器不放长期凭据',
    name: '票据与会话',
  },
  {
    detail: '业务上下文按 FR-13 六个字段，只作用于下一次运行',
    name: '业务上下文',
  },
  {
    detail: '登记路由名 + 类型化参数，拒绝任意 URL/脚本',
    name: '宿主导航事件',
  },
  {
    detail: '自托管资产 + 精确 frame-ancestors + 构建清单白名单',
    name: '嵌入页与安全头',
  },
];

/** 换票说明（宿主后端职责），不含任何真实凭据 */
export const TICKET_HELP = [
  '1) 宿主后端保存应用客户端凭据（appCode + appSecret），不要把凭据放进前端代码或配置。',
  '2) 浏览器向宿主后端请求短期票据；宿主后端用客户端凭据调用 POST /app-api/ai/auth/ticket。',
  '3) 浏览器把票据通过 SDK 的 getAccessToken 回调交给嵌入页，SDK 只把它放进内存并随请求头发送。',
  '4) 票据过期时 SDK 会自动请求续票（同一时刻只发一次），宿主后端只需返回新票据。',
];

export interface IntegrationSnippetOptions {
  appCode: string;
  /** 平台入口基址（默认同源 `/app-api/ai/v1/embed`） */
  embedBasePath?: string;
  mode: ChatDisplayMode;
  serviceId?: string;
}

/**
 * 生成接入片段：**不含真实票据**，只有占位符与宿主后端的换票说明。
 *
 * 生成的代码用的是平台自托管入口（`/app-api/ai/v1/embed/<appCode>`）与宿主自己的 iframe 壳，
 * 不引用任何公共 CDN 或浮动版本路径。
 */
export function buildIntegrationSnippet(
  options: IntegrationSnippetOptions,
): string {
  const base = options.embedBasePath ?? '/app-api/ai/v1/embed';
  const serviceId = options.serviceId ?? 'svc_your_service';
  return [
    '<!-- 宿主页面：把嵌入页放进你选择的位置（自托管入口，不用公共 CDN） -->',
    `<iframe id="ai-chat" src="${base}/${options.appCode}"`,
    '        style="border:0;width:100%;height:640px"',
    '        referrerpolicy="no-referrer" title="AI 助手"></iframe>',
    '',
    '<!-- 票据由宿主后端换取；这里只拿短期票据，不放长期凭据 -->',
    'const ticket = await fetch("/your-backend/ai-ticket").then((r) => r.json());',
    '',
    '// 用 SDK 挂载（模式与主题由宿主决定，主题覆盖只作用于本实例）',
    'import { createChatMount } from "@vben/ai-embed-sdk";',
    'const mount = createChatMount({',
    `  appCode: "${options.appCode}",`,
    '  allowedOrigins: ["https://your-host.example.com"],',
    `  mode: "${options.mode}",`,
    `  serviceId: "${serviceId}",`,
    '  container: document.querySelector("#ai-chat-container"),',
    '  frame: { create: () => document.querySelector("#ai-chat"), destroy: () => {} },',
    '  instanceId: "host-instance-1",',
    '  getAccessToken: async () => ({',
    '    token: ticket.token,',
    '    expiresAt: ticket.expiresAt,',
    '  }),',
    '});',
    'mount.open();',
    '',
    '<!-- 占位符：真实票据形如 <TICKET>，只在运行时由宿主后端返回 -->',
  ].join('\n');
}

/** 接入片段是否**不含**任何疑似真实票据（页面自检 + 单测断言） */
export function containsTicketLiteral(snippet: string): boolean {
  // 真实票据前缀由平台签发（aitkt_）；占位符与说明文字不算
  return /aitkt_[A-Za-z0-9]{8,}/u.test(snippet);
}

/** 默认嵌入入口基址（平台自托管，同源）。 */
export const DEFAULT_EMBED_BASE_PATH = '/app-api/ai/v1/embed';

/** 平台默认入口基址。 */
export function getEmbedBasePath(): string {
  return DEFAULT_EMBED_BASE_PATH;
}

/**
 * 从构建期环境变量读取入口基址（部署在不同域名时使用）。
 *
 * <p>只接受 http(s) 或同源相对路径；其它取值（含协议相对、`javascript:`）一律回落默认值——
 * 这个值会进入复制出去的接入代码，不能成为任意地址的来源。
 */
export function readEmbedBasePathFromEnv(): string {
  const raw = import.meta.env.VITE_AI_EMBED_BASE_PATH as string | undefined;
  if (typeof raw !== 'string' || raw.trim().length === 0) {
    return '';
  }
  const value = raw.trim();
  if (value.startsWith('/') && !value.startsWith('//')) {
    return value;
  }
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:' ? value : '';
  } catch {
    return '';
  }
}
