import type {
  BridgeMessage,
  BridgeTokenRequired,
  BusinessContext,
  Theme,
} from '@vben/ai-contracts';
import type { BridgeState } from '@vben/ai-embed-sdk';

import {
  BRIDGE_PROTOCOL_VERSION,
  isCompatibleProtocolVersion,
  parseBridgeMessage,
  whitelistedBridgeType,
} from '@vben/ai-contracts';

/**
 * 组件内页桥（X09）：**iframe 侧桥（`IframeBridge`）的页内对应实现**。
 *
 * <p>为什么组件还需要一个"桥"：组件路径与 iframe 路径必须遵守**同一份握手协议**
 * （HELLO → READY → AUTH → INIT，业务消息只在 INITIALIZED 之后受理），否则宿主无法用同一套
 * 代码在两种集成方式之间切换。宿主侧直接复用 SDK 的 `HostBridge`（状态机、换票 single-flight、
 * 代次过滤都在那里），本文件只实现嵌入侧：校验来源、把 AUTH 的票据只留在内存、接 INIT 的主题与服务、
 * 按登记顺序拒绝提前的业务消息。
 *
 * <p>与 iframe 侧的唯一差别是"来源"的形态：页内没有跨源边界，`event.source` 是一个**每实例唯一
 * 的身份令牌对象**（宿主侧 `HostBridgeTransport.source()` 返回同一个对象）。于是同页面另一个组件
 * 实例、或任何第三方代码伪造的消息都会在来源比对处被丢弃（不会串票据、不会串事件）。
 */

/** 桥事件（宿主侧只挑出这三个字段，与 iframe 路径同形）。 */
export interface LocalPortEvent {
  data: unknown;
  origin: string;
  source: unknown;
}

/** 传输端口：组件实现真实的投递（页内直接调用）与资源清理。 */
export interface LocalPortTransport {
  destroy(): void;
  post(message: BridgeMessage): void;
}

/** 握手期允许的消息类型（其余业务消息必须在 INIT 之后）。 */
const HANDSHAKE_TYPES = new Set<BridgeMessage['type']>([
  'AUTH',
  'DESTROY',
  'HELLO',
  'INIT',
]);

export interface LocalPortOptions {
  /** 允许域（来自宿主参数；空数组即拒绝构造——不允许退化成"谁都信"） */
  allowedOrigins: string[];
  appCode: string;
  instanceId: string;
  /** 收到 CONTEXT_UPDATE（契约已校验形状）：只作用于下一次运行 */
  onContext?: (context: BusinessContext) => void;
  onDestroyed?: () => void;
  onError?: (error: { errorCode: string; message: string }) => void;
  onInit?: (payload: {
    serviceId: null | string;
    theme: Theme | undefined;
  }) => void;
  onTheme?: (theme: Theme) => void;
  protocolVersion?: string;
  /** 本实例的身份令牌（与宿主侧 transport.source() 是同一个对象） */
  self: unknown;
  transport: LocalPortTransport;
}

export interface LocalPort {
  /** 内存里的短期票据（绝不写 URL/存储/日志）。 */
  credential(): null | { expiresAt: string; token: string };
  currentTheme(): Theme | undefined;
  destroy(): void;
  /** 嵌入侧 → 宿主：报表已创建。 */
  notifyReportCreated(event: {
    reportId: string;
    title?: string;
    version: number;
  }): void;
  /** 处理一条来自宿主侧桥的消息；返回是否被采纳。 */
  receive(event: LocalPortEvent): boolean;
  /**
   * 票据缺失/过期/被撤销：请求宿主重新换取；返回值在新 AUTH 到达时兑现
   * （桥失败/销毁时兑现 `null`，调用方据此走失败分支而不是挂起）。
   */
  renew(reason: BridgeTokenRequired['reason']): Promise<null | string>;
  /** 嵌入侧 → 宿主：请求导航（只带登记路由名与类型化参数）。 */
  requestNavigate(
    route: string,
    params?: Record<string, boolean | number | string>,
  ): void;
  state(): BridgeState;
}

export function createLocalPort(options: LocalPortOptions): LocalPort {
  if (options.allowedOrigins.length === 0) {
    throw new Error('组件桥必须显式给出允许域（精确 Origin）');
  }
  let currentState: BridgeState = 'CREATED';
  let theme: Theme | undefined;
  let token: null | { expiresAt: string; token: string } = null;
  let pendingRenewals: ((token: null | string) => void)[] = [];

  function settleRenewals(value: null | string): void {
    const waiting = pendingRenewals;
    pendingRenewals = [];
    for (const resolve of waiting) {
      resolve(value);
    }
  }

  function message(
    payload:
      | { errorCode: string; message: string; type: 'ERROR' }
      | {
          params: Record<string, boolean | number | string> | undefined;
          route: string;
          type: 'NAVIGATE_REQUEST';
        }
      | { reason: BridgeTokenRequired['reason']; type: 'TOKEN_REQUIRED' }
      | {
          reportId: string;
          title?: string;
          type: 'REPORT_CREATED';
          version: number;
        }
      | { type: 'READY' },
  ): BridgeMessage {
    return {
      ...payload,
      instanceId: options.instanceId,
      protocolVersion: options.protocolVersion ?? BRIDGE_PROTOCOL_VERSION,
    } as BridgeMessage;
  }

  function fail(errorCode: string, text: string): void {
    if (currentState === 'DESTROYED') {
      return;
    }
    options.onError?.({ errorCode, message: text });
    options.transport.post(
      message({ errorCode, message: text, type: 'ERROR' }),
    );
  }

  function teardown(): void {
    token = null;
    theme = undefined;
    currentState = 'DESTROYED';
    settleRenewals(null);
    options.onDestroyed?.();
    options.transport.destroy();
  }

  function acceptAuth(auth: { expiresAt: string; token: string }): boolean {
    if (currentState !== 'WAITING_READY' && currentState !== 'INITIALIZED') {
      fail('MESSAGE_OUT_OF_ORDER', '未发送 READY 之前不接受 AUTH');
      return false;
    }
    // 票据只留在内存：不进 URL、不进 storage、不进日志
    token = { expiresAt: auth.expiresAt, token: auth.token };
    settleRenewals(auth.token);
    return true;
  }

  return {
    credential(): null | { expiresAt: string; token: string } {
      return token === null ? null : { ...token };
    },

    currentTheme(): Theme | undefined {
      return theme;
    },

    destroy(): void {
      if (currentState === 'DESTROYED') {
        return;
      }
      teardown();
    },

    notifyReportCreated(event: {
      reportId: string;
      title?: string;
      version: number;
    }): void {
      if (currentState !== 'INITIALIZED') {
        return;
      }
      options.transport.post(message({ ...event, type: 'REPORT_CREATED' }));
    },

    receive(event: LocalPortEvent): boolean {
      if (currentState === 'DESTROYED') {
        return false;
      }
      if (event.source !== options.self) {
        // 不是本实例的伴生消息（同页面其它组件实例、或第三方伪造）：丢弃且不回 ERROR
        return false;
      }
      if (!options.allowedOrigins.includes(event.origin)) {
        return false;
      }
      let incoming: BridgeMessage;
      try {
        incoming = parseBridgeMessage(event.data);
      } catch {
        const reserved = whitelistedBridgeType(event.data);
        if (reserved === null) {
          return false;
        }
        // 白名单内但本阶段不该出现的消息：明确拒绝，不静默吞掉
        fail(
          currentState === 'WAITING_READY'
            ? 'MESSAGE_OUT_OF_ORDER'
            : 'MESSAGE_NOT_SUPPORTED',
          `本阶段不接受消息 ${reserved}`,
        );
        return false;
      }
      if (incoming.instanceId !== options.instanceId) {
        return false;
      }
      if (
        !isCompatibleProtocolVersion(
          incoming.protocolVersion,
          options.protocolVersion ?? BRIDGE_PROTOCOL_VERSION,
        )
      ) {
        fail('PROTOCOL_VERSION_UNSUPPORTED', '桥协议版本不受支持');
        return false;
      }
      if (
        currentState === 'WAITING_READY' &&
        !HANDSHAKE_TYPES.has(incoming.type)
      ) {
        fail(
          'MESSAGE_OUT_OF_ORDER',
          `未收到 AUTH 之前不接受消息 ${incoming.type}`,
        );
        return false;
      }
      switch (incoming.type) {
        case 'AUTH': {
          return acceptAuth(incoming);
        }
        case 'CONTEXT_UPDATE': {
          // 只更新"下一次运行"的上下文；已受理的运行使用受理瞬间的快照。
          // 形状已由契约校验；消费方（宿主侧仓库）再校验一次，失败按稳定错误码回执而不抛穿桥。
          try {
            options.onContext?.(incoming.context);
          } catch {
            fail('CONTEXT_SCHEMA_INVALID', '业务上下文不符合契约');
            return false;
          }
          return true;
        }
        case 'DESTROY': {
          teardown();
          return true;
        }
        case 'ERROR': {
          options.onError?.({
            errorCode: incoming.errorCode,
            message: incoming.message,
          });
          settleRenewals(null);
          return true;
        }
        case 'HELLO': {
          // 宿主声明"你应该接哪个应用"：与本组件实例的应用标识不一致即拒绝
          if (incoming.appCode !== options.appCode) {
            fail('APP_MISMATCH', '宿主声明的应用与组件实例不一致');
            return false;
          }
          // 再次 HELLO（宿主切用户/重新握手）：丢弃旧票据与旧主题，重新 READY
          token = null;
          theme = undefined;
          settleRenewals(null);
          currentState = 'WAITING_READY';
          options.transport.post(message({ type: 'READY' }));
          return true;
        }
        case 'INIT': {
          if (currentState === 'INITIALIZED') {
            return false;
          }
          if (incoming.theme !== undefined) {
            // 主题在契约层已校验形状；这里只保存（观感由渲染层的令牌驱动）
            theme = incoming.theme;
          }
          currentState = 'INITIALIZED';
          options.onInit?.({
            serviceId: incoming.serviceId ?? null,
            theme,
          });
          return true;
        }
        case 'NAVIGATE_REQUEST':
        case 'REPORT_CREATED': {
          // 这两个方向是嵌入侧 → 宿主；宿主发来即为乱序（不伪装成功）
          fail('MESSAGE_OUT_OF_ORDER', `${incoming.type} 只能由嵌入侧发往宿主`);
          return false;
        }
        case 'THEME_UPDATE': {
          // 只换观感：不重置会话、不清上下文、不重建组件
          theme = incoming.theme;
          options.onTheme?.(incoming.theme);
          return true;
        }
        default: {
          // 白名单内但本版本尚未处理的消息（OPEN/CLOSE）：明确拒绝而不是静默丢弃
          fail('MESSAGE_NOT_SUPPORTED', `本版本尚未处理消息 ${incoming.type}`);
          return false;
        }
      }
    },

    renew(reason: BridgeTokenRequired['reason']): Promise<null | string> {
      if (currentState !== 'INITIALIZED') {
        return Promise.resolve(null);
      }
      token = null;
      options.transport.post(message({ reason, type: 'TOKEN_REQUIRED' }));
      return new Promise<null | string>((resolve) => {
        pendingRenewals.push(resolve);
      });
    },

    requestNavigate(
      route: string,
      params?: Record<string, boolean | number | string>,
    ): void {
      if (currentState !== 'INITIALIZED') {
        return;
      }
      options.transport.post(
        message({ params, route, type: 'NAVIGATE_REQUEST' }),
      );
    },

    state(): BridgeState {
      return currentState;
    },
  };
}
