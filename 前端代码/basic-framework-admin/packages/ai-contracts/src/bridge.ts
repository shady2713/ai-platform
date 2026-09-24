import { z } from 'zod';

import { themeSchema } from './theme';

/**
 * 嵌入桥协议（C06）：宿主页面与嵌入 iframe 之间**唯一**的通信契约。
 *
 * <p>设计原则（对应设计契约 7.2 的白名单与校验要求）：
 * <ol>
 *   <li><b>判别联合 + 严格键</b>：未知 `type` 与多余字段一律拒绝，不用"尽力解析"；
 *       白名单由 {@link BRIDGE_MESSAGE_TYPES} 固定，新增消息类型必须同时改这里与两侧实现。</li>
 *   <li><b>每条消息自带身份与版本</b>：`instanceId` 用于实例隔离（同一页面可挂多个不同应用的 Chat），
 *       `protocolVersion` 用于版本协商；两者都由接收方校验，不信任消息来源的自我声明。</li>
 *   <li><b>凭据只走 AUTH</b>：`token` 只出现在宿主 → iframe 的 AUTH 消息里，且接收方只保存在内存；
 *       协议不含任何把凭据写进 URL/存储的字段。</li>
 *   <li><b>业务消息与握手消息分开</b>：未进入 INITIALIZED 之前，接收方必须拒绝业务消息
 *       （见两侧实现的"提前业务消息拒绝"）。</li>
 * </ol>
 */

/** 当前桥协议版本（N/N-1 兼容由接收方的版本协商负责）。 */
export const BRIDGE_PROTOCOL_VERSION = '1.0';

/** 消息白名单（顺序无关；两侧实现只能处理这里的类型）。 */
export const BRIDGE_MESSAGE_TYPES = [
  'AUTH',
  'CLOSE',
  'CONTEXT_UPDATE',
  'DESTROY',
  'ERROR',
  'HELLO',
  'INIT',
  'NAVIGATE_REQUEST',
  'OPEN',
  'READY',
  'REPORT_CREATED',
  'THEME_UPDATE',
  'TOKEN_REQUIRED',
] as const;

export type BridgeMessageType = (typeof BRIDGE_MESSAGE_TYPES)[number];

const INSTANCE_ID = z
  .string()
  .min(1)
  .max(64)
  .regex(/^[\w-]+$/u, '实例标识只允许字母数字与 -_');
const PROTOCOL_VERSION = z
  .string()
  .min(1)
  .max(16)
  .regex(/^\d+\.\d+$/u, '协议版本形如 1.0');

const envelope = {
  instanceId: INSTANCE_ID,
  protocolVersion: PROTOCOL_VERSION,
};

/** iframe → 宿主：宣告上线并给出自己的应用与协议版本。 */
export const bridgeHelloSchema = z
  .object({
    ...envelope,
    appCode: z.string().min(1).max(64),
    type: z.literal('HELLO'),
  })
  .strict();

/** iframe → 宿主：已就绪（可接收 AUTH 与 INIT）。 */
export const bridgeReadySchema = z
  .object({
    ...envelope,
    themeRevision: z.number().int().min(0).max(1_000_000).nullable().optional(),
    type: z.literal('READY'),
  })
  .strict();

/** 宿主 → iframe：短期票据（只在内存保存，不进 URL/存储）。 */
export const bridgeAuthSchema = z
  .object({
    ...envelope,
    expiresAt: z.string().min(1).max(40),
    token: z.string().min(8).max(4096),
    type: z.literal('AUTH'),
  })
  .strict();

/** 宿主 → iframe：初始化（服务、主题；业务上下文由 C08 扩展）。 */
export const bridgeInitSchema = z
  .object({
    ...envelope,
    serviceId: z.string().min(1).max(64).nullable().optional(),
    theme: themeSchema.optional(),
    type: z.literal('INIT'),
  })
  .strict();

/** iframe → 宿主：票据缺失/过期/被撤销，请求重新换取。 */
export const bridgeTokenRequiredSchema = z
  .object({
    ...envelope,
    reason: z.enum(['EXPIRED', 'MISSING', 'REVOKED']),
    type: z.literal('TOKEN_REQUIRED'),
  })
  .strict();

/** 双向：错误（稳定错误码 + 可读说明，不含凭据与正文）。 */
export const bridgeErrorSchema = z
  .object({
    ...envelope,
    errorCode: z.string().min(1).max(64),
    message: z.string().max(500),
    type: z.literal('ERROR'),
  })
  .strict();

/** 宿主 → iframe：销毁（关闭流、清 token、移除监听器与 DOM）。 */
export const bridgeDestroySchema = z
  .object({
    ...envelope,
    type: z.literal('DESTROY'),
  })
  .strict();

export const bridgeMessageSchema = z.discriminatedUnion('type', [
  bridgeHelloSchema,
  bridgeReadySchema,
  bridgeAuthSchema,
  bridgeInitSchema,
  bridgeTokenRequiredSchema,
  bridgeErrorSchema,
  bridgeDestroySchema,
]);

export type BridgeMessage = z.infer<typeof bridgeMessageSchema>;

export type BridgeHello = z.infer<typeof bridgeHelloSchema>;

export type BridgeReady = z.infer<typeof bridgeReadySchema>;

export type BridgeAuth = z.infer<typeof bridgeAuthSchema>;

export type BridgeInit = z.infer<typeof bridgeInitSchema>;

export type BridgeTokenRequired = z.infer<typeof bridgeTokenRequiredSchema>;

export type BridgeError = z.infer<typeof bridgeErrorSchema>;

export type BridgeDestroy = z.infer<typeof bridgeDestroySchema>;

/**
 * 解析桥消息；任何不合规输入（未知类型、多余字段、非法实例标识/版本、超长字段）都抛错。
 *
 * <p>调用方**不得**把未校验的消息交给业务逻辑：协议层拒绝是唯一的入口校验。
 */
export function parseBridgeMessage(input: unknown): BridgeMessage {
  return bridgeMessageSchema.parse(input);
}

/**
 * 取出"在白名单内但本版本尚未建模"的消息类型（否则返回 null）。
 *
 * <p>用途：接收方对未实现的白名单消息要给出**明确**错误（`MESSAGE_NOT_SUPPORTED`），
 * 而不是与真正的非法输入混为一谈（后者才是 schema 非法）。
 */
export function whitelistedBridgeType(
  input: unknown,
): BridgeMessageType | null {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    return null;
  }
  const type = (input as { type?: unknown }).type;
  return typeof type === 'string' &&
    (BRIDGE_MESSAGE_TYPES as readonly string[]).includes(type)
    ? (type as BridgeMessageType)
    : null;
}

/**
 * 版本是否兼容：同版本，或接收方比发送方高一个小版本（N-1 兼容）。
 *
 * <p>主版本不同一律不兼容（协议语义可能已变），此时接收方必须拒绝并回 ERROR。
 */
export function isCompatibleProtocolVersion(
  received: string,
  supported: string,
): boolean {
  const [receivedMajor, receivedMinor] = received.split('.').map(Number);
  const [supportedMajor, supportedMinor] = supported.split('.').map(Number);
  if (
    receivedMajor === undefined ||
    receivedMinor === undefined ||
    supportedMajor === undefined ||
    supportedMinor === undefined ||
    Number.isNaN(receivedMajor) ||
    Number.isNaN(receivedMinor) ||
    Number.isNaN(supportedMajor) ||
    Number.isNaN(supportedMinor)
  ) {
    return false;
  }
  if (receivedMajor !== supportedMajor) {
    return false;
  }
  // 接收方版本不低于发送方，且差距不超过一个小版本
  return receivedMinor <= supportedMinor && supportedMinor - receivedMinor <= 1;
}
