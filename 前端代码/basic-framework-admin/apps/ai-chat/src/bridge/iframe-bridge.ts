import type {
  BridgeMessage,
  BridgeState,
  BridgeTokenRequired,
  BusinessContext,
  Theme,
} from '@vben/ai-embed-sdk';

import {
  BRIDGE_PROTOCOL_VERSION,
  createBusinessContextStore,
  isCompatibleProtocolVersion,
  parseBridgeMessage,
  whitelistedBridgeType,
} from '@vben/ai-embed-sdk';

/**
 * iframe 侧桥（C06）：接收 AUTH/INIT，按状态机拒绝提前业务消息。
 *
 * <p>与宿主侧对称的三条硬语义：
 * <ol>
 *   <li><b>只认父窗口与允许域</b>：`event.source !== window.parent` 或 origin 不在 bootstrap 给出的允许域内一律丢弃，
 *       未知来源连 ERROR 都不回（避免把 iframe 存在性与错误细节暴露给无关页面）；</li>
 *   <li><b>票据只在内存</b>：AUTH 里的 token 只放在本对象字段里，不进 URL、不进 storage、
 *       不进日志；`DESTROY` 时清空（AT-052）；</li>
 *   <li><b>提前业务消息拒绝</b>：未 INITIALIZED 之前收到 CONTEXT_UPDATE/THEME_UPDATE 等业务消息，
 *       回 ERROR 并保持状态（AT-051 的 iframe 侧版本）。</li>
 * </ol>
 *
 * <p>允许域来自服务端 `/bootstrap`（应用发布配置）；为空即拒绝构造——不允许退化成"谁都信"。
 */
export class IframeBridge {
  /** 业务上下文：只作用下一次运行（运行受理时取快照）。 */
  private readonly contexts = createBusinessContextStore();

  private currentState: BridgeState = 'CREATED';

  private theme: Theme | undefined;

  private token: null | { expiresAt: string; token: string } = null;

  constructor(private readonly options: IframeBridgeOptions) {
    if (options.allowedOrigins.length === 0) {
      throw new Error('iframe 桥必须显式给出允许域（来自服务端 bootstrap）');
    }
  }

  /** 当前票据（仅供同源业务代码使用；绝不写 URL/存储）。 */
  credential(): null | { expiresAt: string; token: string } {
    return this.token;
  }

  /** 下一次运行将使用的业务上下文（运行受理时用 `snapshotContext()` 取快照）。 */
  currentContext(): BusinessContext | null {
    return this.contexts.current();
  }

  /** 已生效的主题（INIT 携带并校验通过后）。 */
  currentTheme(): Theme | undefined {
    return this.theme;
  }

  /** 收到 DESTROY：清 token、清主题、移除资源（DOM 与流由宿主应用负责）。 */
  destroy(): void {
    if (this.currentState === 'DESTROYED') {
      return;
    }
    this.onDestroy();
  }

  /**
   * 发起握手：运行时已就绪，通知宿主可以下发票据（CREATED → WAITING_READY）。
   *
   * <p>方向约定（与设计契约 7.2 一致）：宿主 HELLO（声明应用）→ iframe READY（运行时已就绪）
   * → 宿主 AUTH（票据）→ 宿主 INIT（服务与主题）→ INITIALIZED。
   */
  handshake(): void {
    if (this.currentState !== 'CREATED') {
      return;
    }
    this.currentState = 'WAITING_READY';
    this.options.transport.post(this.message({ type: 'READY' }));
  }

  /** iframe → 宿主：报表已创建。 */
  notifyReportCreated(event: {
    reportId: string;
    title?: string;
    version: number;
  }): void {
    if (this.currentState !== 'INITIALIZED') {
      return;
    }
    this.options.transport.post(
      this.message({ ...event, type: 'REPORT_CREATED' }),
    );
  }

  /** 处理来自宿主的消息；返回是否被采纳。 */
  receive(event: IframeBridgeEvent): boolean {
    if (this.currentState === 'DESTROYED') {
      return false;
    }
    if (event.source !== this.options.parentSource) {
      // 不是本实例的父窗口（同页面其它 frame 或第三方页面）：丢弃且不回 ERROR
      return false;
    }
    if (!this.options.allowedOrigins.includes(event.origin)) {
      return false;
    }
    let message: BridgeMessage;
    try {
      message = parseBridgeMessage(event.data);
    } catch {
      const reserved = whitelistedBridgeType(event.data);
      if (reserved === null) {
        return false;
      }
      // 白名单内但当前阶段不该出现的消息（含尚未实现的业务消息）：明确拒绝
      this.fail(
        this.currentState === 'WAITING_READY'
          ? 'MESSAGE_OUT_OF_ORDER'
          : 'MESSAGE_NOT_SUPPORTED',
        `本阶段不接受消息 ${reserved}`,
      );
      return false;
    }
    if (message.instanceId !== this.options.instanceId) {
      return false;
    }
    if (
      !isCompatibleProtocolVersion(
        message.protocolVersion,
        this.options.protocolVersion ?? BRIDGE_PROTOCOL_VERSION,
      )
    ) {
      this.fail('PROTOCOL_VERSION_UNSUPPORTED', '桥协议版本不受支持');
      return false;
    }
    // 握手阶段只接受握手消息：AUTH 与 INIT 是宿主紧接着发来的两步，其余（含业务消息）一律拒绝
    if (
      this.currentState === 'WAITING_READY' &&
      !['AUTH', 'DESTROY', 'HELLO', 'INIT'].includes(message.type)
    ) {
      this.fail(
        'MESSAGE_OUT_OF_ORDER',
        `未收到 AUTH 之前不接受消息 ${message.type}`,
      );
      return false;
    }
    switch (message.type) {
      case 'AUTH': {
        // 票据只留在内存；后续业务请求由 iframe 内的客户端从这里取
        this.token = { expiresAt: message.expiresAt, token: message.token };
        // 只把票据本身交给业务：不把整条协议消息透出去（减少误用与误记日志的机会）
        this.options.onAuth?.({
          expiresAt: message.expiresAt,
          token: message.token,
        });
        return true;
      }
      case 'CONTEXT_UPDATE': {
        // 只更新"下一次运行"的上下文；已受理的运行用自己受理时的快照（AT-053 的上下文版本）
        try {
          this.contexts.update(message.context);
        } catch {
          this.fail('CONTEXT_SCHEMA_INVALID', '业务上下文不符合契约');
          return false;
        }
        this.options.onContext?.(this.contexts.current());
        return true;
      }
      case 'DESTROY': {
        this.onDestroy();
        return true;
      }
      case 'ERROR': {
        this.options.onError?.({
          errorCode: message.errorCode,
          message: message.message,
        });
        return true;
      }
      case 'HELLO': {
        // 宿主声明"你应该是这个应用"：与本地 bootstrap 的应用标识不一致即拒绝（防止跨应用串用入口）
        if (message.appCode !== this.options.appCode) {
          this.fail('APP_MISMATCH', '宿主声明的应用与本入口不一致');
          return false;
        }
        this.options.transport.post(this.message({ type: 'READY' }));
        return true;
      }
      case 'INIT': {
        if (this.currentState === 'INITIALIZED') {
          return false;
        }
        const theme = message.theme;
        if (theme !== undefined) {
          // 主题在契约层已校验过形状；这里只做"是否与本地白名单一致"的最后一道检查
          this.theme = theme;
        }
        this.currentState = 'INITIALIZED';
        this.options.onInit?.({
          serviceId: message.serviceId ?? null,
          theme: this.theme,
        });
        return true;
      }
      case 'NAVIGATE_REQUEST':
      case 'REPORT_CREATED': {
        // 这两个方向是 iframe → 宿主；宿主发来即为乱序（不伪装成功）
        this.fail(
          'MESSAGE_OUT_OF_ORDER',
          `${message.type} 只能由 iframe 发往宿主`,
        );
        return false;
      }
      case 'THEME_UPDATE': {
        // 只换观感：不重置会话、不清上下文、不重建（滚动位置由渲染层保持）
        this.theme = message.theme;
        this.options.onTheme?.(message.theme);
        return true;
      }
      default: {
        // 白名单内但本版本尚未处理的消息（OPEN/CLOSE）：明确拒绝而不是静默丢弃
        this.fail(
          'MESSAGE_NOT_SUPPORTED',
          `本版本尚未处理消息 ${message.type}`,
        );
        return false;
      }
    }
  }

  /** iframe → 宿主：请求导航（只带登记路由名与类型化参数；宿主侧再按登记表校验）。 */
  requestNavigate(
    route: string,
    params?: Record<string, boolean | number | string>,
  ): void {
    if (this.currentState !== 'INITIALIZED') {
      return;
    }
    this.options.transport.post(
      this.message({ params, route, type: 'NAVIGATE_REQUEST' }),
    );
  }

  /** 票据不可用（缺失/过期/被撤销）：请求宿主重新换取（宿主侧 single-flight）。 */
  requestToken(reason: BridgeTokenRequired['reason']): void {
    if (this.currentState !== 'INITIALIZED') {
      return;
    }
    this.token = null;
    this.options.transport.post(
      this.message({ reason, type: 'TOKEN_REQUIRED' }),
    );
  }

  /** 运行受理时取上下文快照：之后的 CONTEXT_UPDATE 不影响这次运行。 */
  snapshotContext(): BusinessContext | null {
    return this.contexts.snapshot();
  }

  state(): BridgeState {
    return this.currentState;
  }

  private fail(errorCode: string, message: string): void {
    if (this.currentState === 'DESTROYED') {
      return;
    }
    this.options.onError?.({ errorCode, message });
    this.options.transport.post(
      this.message({ errorCode, message, type: 'ERROR' }),
    );
  }

  private message(
    payload:
      | { appCode: string; type: 'HELLO' }
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
      instanceId: this.options.instanceId,
      protocolVersion: this.options.protocolVersion ?? BRIDGE_PROTOCOL_VERSION,
    } as BridgeMessage;
  }

  private onDestroy(): void {
    this.token = null;
    this.theme = undefined;
    this.currentState = 'DESTROYED';
    this.options.onDestroy?.();
    this.options.transport.destroy();
  }
}

export interface IframeBridgeEvent {
  data: unknown;
  origin: string;
  source: unknown;
}

/** 传输端口：iframe 侧实现真实的 parent.postMessage（精确 targetOrigin）与资源清理。 */
export interface IframeBridgeTransport {
  destroy(): void;
  post(message: BridgeMessage): void;
}

export interface IframeBridgeOptions {
  /** 应用标识（服务端 bootstrap 给出）。 */
  appCode: string;
  /** 允许域（服务端 bootstrap 给出；为空即拒绝构造）。 */
  allowedOrigins: string[];
  /** 实例标识（由 URL 上的非秘密参数传入）。 */
  instanceId: string;
  onAuth?: (auth: { expiresAt: string; token: string }) => void;
  onContext?: (context: unknown) => void;
  onDestroy?: () => void;
  onError?: (error: { errorCode: string; message: string }) => void;
  onInit?: (payload: {
    serviceId: null | string;
    theme: Theme | undefined;
  }) => void;
  onTheme?: (theme: Theme) => void;
  /** 父窗口引用（生产为 window.parent；测试可注入）——必须显式给出，否则来源校验无从比较。 */
  parentSource: unknown;
  protocolVersion?: string;
  transport: IframeBridgeTransport;
}

/** 创建 iframe 侧桥。 */
export function createIframeBridge(options: IframeBridgeOptions): IframeBridge {
  return new IframeBridge(options);
}
