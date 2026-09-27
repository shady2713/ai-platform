import type { BridgeMessage, Theme } from '@vben/ai-contracts';

import type { HostEventHandlers } from '../events/host-events';
import type { BridgeState } from '../types';

import {
  BRIDGE_PROTOCOL_VERSION,
  isCompatibleProtocolVersion,
  parseBridgeMessage,
  whitelistedBridgeType,
} from '@vben/ai-contracts';

export type { BridgeState };

/**
 * 宿主侧桥实例（C06）：握手、换票去重与实例隔离。
 *
 * <p>为什么把它做成"注入传输端口 + 纯状态机"而不是直接操作 window：
 * 跨源安全的关键判断（origin / source / instanceId / 版本 / schema）必须能被逐条测试，
 * 而真实 iframe 在这些判断里只会添噪声。宿主提供 {@link HostBridgeTransport} 完成真实的
 * `postMessage`（用**精确 targetOrigin**）与清理，本类只负责"该不该处理这条消息、下一步发什么"。
 *
 * <p>三条硬语义（与验收对应）：
 * <ol>
 *   <li><b>恶意 frame 送不进 AUTH</b>：只处理 origin 在应用允许域内、且 `event.source` 就是本实例 iframe 的消息；
 *       任何 READY 之外的前置消息都不触发换票（AT-051）；</li>
 *   <li><b>两个 app 不串 token</b>：每个实例持有自己的实例标识与允许域，AUTH 只发给本实例的 iframe
 *       （AT-052 的宿主侧版本）；</li>
 *   <li><b>旧实例/旧代次消息丢弃</b>：`resetSession()` 使代次 +1，晚到的异步结果与事件按代次过滤
 *       （AT-053，与 C02 的会话状态机同一口径）；</li>
 *   <li><b>业务事件按实例路由</b>：`NAVIGATE_REQUEST`/`REPORT_CREATED` 只在 INITIALIZED 后、且
 *       origin/source/instanceId/协议版本/schema 全部通过时才交给宿主回调；导航的路由注册与
 *       参数类型由 C08 的宿主事件处理器（`createHostEventHandlers`）判定，未登记一律拒绝。</li>
 * </ol>
 */
export class HostBridge {
  private currentState: BridgeState = 'CREATED';

  private generation = 0;

  private tokenRequest: null | Promise<
    | { credential: null; ok: false }
    | { credential: { expiresAt: string; token: string }; ok: true }
  > = null;

  constructor(private readonly options: HostBridgeOptions) {
    if (options.allowedOrigins.length === 0) {
      // 允许域为空意味着"不知道能信谁"：此时不允许进入握手，避免退化成"谁都信"
      throw new Error('桥实例必须显式给出允许域（精确 Origin）');
    }
  }

  currentGeneration(): number {
    return this.generation;
  }

  /** 销毁：状态单向到 DESTROYED，清理传输层资源（监听器/DOM 由传输实现负责）。 */
  destroy(): void {
    if (this.currentState === 'DESTROYED') {
      return;
    }
    this.currentState = 'DESTROYED';
    this.tokenRequest = null;
    this.options.transport.destroy();
  }

  /**
   * 处理一条来自 iframe 的消息；返回是否被采纳。
   *
   * <p>校验顺序固定为：实例已销毁 → 来源 origin/source → 实例号 → 协议版本 → schema → 状态机。
   * 任一不通过都丢弃（可选回 ERROR），绝不"先处理再判断"。
   */
  receive(event: HostBridgeEvent): boolean {
    if (this.currentState === 'DESTROYED') {
      return false;
    }
    if (!this.options.allowedOrigins.includes(event.origin)) {
      this.options.onRejected?.('ORIGIN_NOT_ALLOWED');
      return false;
    }
    if (event.source !== this.options.transport.source()) {
      // 来源不是本实例的 iframe（例如页面里另一个 frame 冒充）：丢弃
      this.options.onRejected?.('SOURCE_MISMATCH');
      return false;
    }
    let message: BridgeMessage;
    try {
      message = parseBridgeMessage(event.data);
    } catch {
      const reserved = whitelistedBridgeType(event.data);
      if (reserved === null) {
        this.options.onRejected?.('SCHEMA_INVALID');
        return false;
      }
      // 白名单内但本版本尚未实现的消息：明确拒绝，不静默吞掉
      this.fail(
        'MESSAGE_NOT_SUPPORTED',
        `本版本尚未处理消息 ${reserved}`,
        event.origin,
      );
      return false;
    }
    if (message.instanceId !== this.options.instanceId) {
      this.options.onRejected?.('INSTANCE_MISMATCH');
      return false;
    }
    if (
      !isCompatibleProtocolVersion(
        message.protocolVersion,
        this.options.protocolVersion ?? BRIDGE_PROTOCOL_VERSION,
      )
    ) {
      this.fail(
        'PROTOCOL_VERSION_UNSUPPORTED',
        '桥协议版本不受支持',
        event.origin,
      );
      return false;
    }
    return this.dispatch(message, this.generation, event.origin);
  }

  /** 用户切换/重新握手：代次 +1、清掉在途换票、回到等待 READY（旧代次的异步结果会被丢弃）。 */
  resetSession(): void {
    if (this.currentState === 'DESTROYED') {
      return;
    }
    this.generation += 1;
    this.tokenRequest = null;
    this.currentState = 'WAITING_READY';
    this.options.transport.post(
      this.message({ appCode: this.options.appCode, type: 'HELLO' }),
    );
  }

  /** 开始握手：CREATED → WAITING_READY，并发送 HELLO。 */
  start(): void {
    if (this.currentState !== 'CREATED') {
      return;
    }
    this.currentState = 'WAITING_READY';
    this.options.transport.post(
      this.message({ appCode: this.options.appCode, type: 'HELLO' }),
    );
  }

  state(): BridgeState {
    return this.currentState;
  }

  /** AUTH → INIT：换票 single-flight，成功后立刻进入 INITIALIZED。 */
  private async authenticate(
    generation: number,
    origin: string,
  ): Promise<void> {
    this.currentState = 'AUTHENTICATING';
    const credential = await this.issueToken(generation);
    if (credential === null) {
      return;
    }
    if (this.stale(generation)) {
      // 期间发生过切用户/销毁：结果作废，不把旧票据发给新会话
      return;
    }
    this.options.transport.post(
      this.message({
        expiresAt: credential.expiresAt,
        token: credential.token,
        type: 'AUTH',
      }),
    );
    this.options.transport.post(
      this.message({
        serviceId: this.options.serviceId ?? null,
        theme: this.options.theme,
        type: 'INIT',
      }),
    );
    this.currentState = 'INITIALIZED';
    this.options.onReady?.();
    void origin;
  }

  private dispatch(
    message: BridgeMessage,
    generation: number,
    origin: string,
  ): boolean {
    switch (message.type) {
      case 'DESTROY': {
        this.destroy();
        return true;
      }
      case 'ERROR': {
        this.options.onError?.({
          errorCode: message.errorCode,
          message: message.message,
        });
        return true;
      }
      case 'HELLO':
      case 'READY': {
        if (this.currentState !== 'WAITING_READY') {
          // 重复的 READY 不重复换票（连续点击/重连风暴下不能产生第二个运行）
          return false;
        }
        void this.authenticate(generation, origin);
        return true;
      }
      case 'NAVIGATE_REQUEST': {
        // 业务事件只在 INITIALIZED 后受理（与 CONTEXT_UPDATE 的提前拒绝同一口径）
        if (this.currentState !== 'INITIALIZED') {
          return this.unsupported(message.type, origin, '启动阶段不接受消息');
        }
        const navigate = this.options.onNavigate;
        if (navigate === undefined) {
          // 宿主没有登记导航处理：明确拒绝，不静默吞掉
          return this.unsupported(message.type, origin, '宿主未登记处理');
        }
        // 路由注册与参数类型由 C08 的宿主校验器判定（未登记路由/夹带 URL 一律拒绝）
        const result = navigate({
          params: message.params,
          route: message.route,
        });
        if (!result.ok) {
          this.options.onRejected?.(result.reason);
          return false;
        }
        return true;
      }
      case 'REPORT_CREATED': {
        if (this.currentState !== 'INITIALIZED') {
          return this.unsupported(message.type, origin, '启动阶段不接受消息');
        }
        const reportCreated = this.options.onReportCreated;
        if (reportCreated === undefined) {
          return this.unsupported(message.type, origin, '宿主未登记处理');
        }
        reportCreated(message);
        return true;
      }
      case 'TOKEN_REQUIRED': {
        if (this.currentState !== 'INITIALIZED') {
          return false;
        }
        void this.authenticate(generation, origin);
        return true;
      }
      default: {
        // 白名单里但本阶段尚未实现的业务消息：明确拒绝，不静默吞掉
        return this.unsupported(message.type, origin, '本版本尚未处理消息');
      }
    }
  }

  private fail(errorCode: string, message: string, _origin: string): void {
    this.options.onError?.({ errorCode, message });
    if (this.currentState !== 'DESTROYED') {
      this.options.transport.post(
        this.message({ errorCode, message, type: 'ERROR' }),
      );
    }
  }

  /**
   * 换票 single-flight：**并发**请求共享一次宿主回调，但结果不缓存——
   * TOKEN_REQUIRED（过期/撤销）必须真正重新换取，否则续票会拿回过期票据。
   */
  private issueToken(
    generation: number,
  ): Promise<null | { expiresAt: string; token: string }> {
    if (this.tokenRequest === null) {
      this.tokenRequest = this.options
        .getAccessToken()
        .then((credential) => ({ credential, ok: true as const }))
        .catch(() => ({ credential: null, ok: false as const }))
        .then((outcome) => {
          // 结算后清空：单飞只覆盖"同一时刻的并发"，不把票据缓存成"以后都能用"
          this.tokenRequest = null;
          return outcome;
        });
    }
    return this.tokenRequest.then((outcome) => {
      if (this.stale(generation)) {
        return null;
      }
      if (!outcome.ok || outcome.credential === null) {
        this.fail(
          'TOKEN_UNAVAILABLE',
          '宿主未能提供访问票据',
          this.options.allowedOrigins[0] ?? '',
        );
        return null;
      }
      return outcome.credential;
    });
  }

  private message(
    payload:
      | { appCode: string; type: 'HELLO' }
      | { errorCode: string; message: string; type: 'ERROR' }
      | { expiresAt: string; token: string; type: 'AUTH' }
      | {
          serviceId: null | string | undefined;
          theme: Theme | undefined;
          type: 'INIT';
        }
      | { type: 'DESTROY' },
  ): BridgeMessage {
    return {
      ...payload,
      instanceId: this.options.instanceId,
      protocolVersion: this.options.protocolVersion ?? BRIDGE_PROTOCOL_VERSION,
    } as BridgeMessage;
  }

  private stale(generation: number): boolean {
    return generation !== this.generation || this.currentState === 'DESTROYED';
  }

  /**
   * 明确拒绝一条白名单内但本实例不处理的消息：回 ERROR 并记录稳定错误码。
   *
   * <p>不静默丢弃是刻意的：宿主侧"没有接这个能力"必须可观测，否则真实接线里
   * 事件会无声消失（C08 宿主校验器拿不到输入时曾经如此）。
   */
  private unsupported(
    type: BridgeMessage['type'],
    origin: string,
    reason: string,
  ): false {
    this.fail('MESSAGE_NOT_SUPPORTED', `${reason} ${type}`, origin);
    return false;
  }
}

/** 宿主事件（调用方从 window 的 message 事件里挑出这三个字段）。 */
export interface HostBridgeEvent {
  data: unknown;
  origin: string;
  source: unknown;
}

/** 传输端口：宿主实现真实的 postMessage（**精确 targetOrigin**）与资源清理。 */
export interface HostBridgeTransport {
  destroy(): void;
  post(message: BridgeMessage): void;
  /** 本实例 iframe 的 window（用于来源比对）。 */
  source(): unknown;
}

export interface HostBridgeOptions {
  /** 应用允许域（精确 Origin，来自应用发布配置；为空即拒绝构造）。 */
  allowedOrigins: string[];
  /** 嵌入的应用标识（HELLO 里声明，iframe 侧据此确认自己就是该应用的入口）。 */
  appCode: string;
  /** 换票回调：必须调用宿主自己的后端，不在回调里放长期 secret。 */
  getAccessToken: () => Promise<{ expiresAt: string; token: string }>;
  /** 实例标识（同一页面挂多个实例时用于隔离）。 */
  instanceId: string;
  onError?: (error: { errorCode: string; message: string }) => void;
  /**
   * C08 宿主事件入口：iframe 的导航请求（`createHostEventHandlers(...).navigate`）。
   *
   * <p>只在 INITIALIZED 后受理，且 origin/source/instanceId/协议版本/schema 全部通过后才调用；
   * 未登记路由与未声明参数由该回调按登记表拒绝（拒绝原因经 `onRejected` 可观测）。
   * 不提供即视为宿主未登记该能力：回 `MESSAGE_NOT_SUPPORTED`（不静默吞掉）。
   */
  onNavigate?: HostEventHandlers['navigate'];
  onReady?: () => void;
  /** 被拒绝的消息原因（观测用；不含消息正文）。 */
  onRejected?: (reason: string) => void;
  /**
   * C08 宿主事件入口：报表已创建通知（`createHostEventHandlers(...).reportCreated`）。
   * 不提供即回 `MESSAGE_NOT_SUPPORTED`。
   */
  onReportCreated?: HostEventHandlers['reportCreated'];
  /** 桥协议版本（默认当前版本）。 */
  protocolVersion?: string;
  /** INIT 携带的服务标识（可空：由服务端发布配置决定）。 */
  serviceId?: null | string;
  /** INIT 携带的宿主主题（可选；iframe 侧按契约再校验一次）。 */
  theme?: Theme;
  transport: HostBridgeTransport;
}

/** 创建宿主侧桥实例。 */
export function createHostBridge(options: HostBridgeOptions): HostBridge {
  return new HostBridge(options);
}
