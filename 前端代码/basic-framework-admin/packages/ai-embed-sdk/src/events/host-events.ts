import type {
  BridgeNavigateRequest,
  BridgeReportCreated,
} from '@vben/ai-contracts';

/**
 * 宿主事件（C08）：iframe 请求的导航与报表事件，**按登记路由与类型化参数**校验后才交给宿主。
 *
 * <p>为什么宿主不能直接执行 iframe 的请求：请求来自页面内（模型输出也可能促成它），
 * 一旦允许"任意 URL / 任意脚本"，嵌入就变成了开放重定向与脚本执行入口。
 * 因此这里只认**宿主自己登记的路由名**与**声明过的参数名/类型**：
 *
 * <ul>
 *   <li>`route` 必须是登记名（未登记 → 拒绝，并在事件里带上稳定原因）；</li>
 *   <li>`params` 只允许登记过且类型匹配的键（多一个键即拒绝，不做"忽略未知参数"）；</li>
 *   <li>取值只允许标量（字符串/有限数字/布尔）；任何 URL、脚本、嵌套对象都不在协议内；</li>
 *   <li>拒绝是**正常结果**：返回 `{ ok: false, reason }`，宿主据此选择忽略或提示，不需要抛异常。</li>
 * </ul>
 */

/** 参数类型（登记表用）。 */
export type HostRouteParamType = 'boolean' | 'number' | 'string';

export interface HostRouteDefinition {
  params?: Record<string, HostRouteParamType>;
}

/** 登记路由表：路由名 → 允许的参数与类型。 */
export type HostRouteRegistry = Record<string, HostRouteDefinition>;

export interface HostNavigationAccepted {
  ok: true;
  params: Record<string, boolean | number | string>;
  route: string;
}

export interface HostNavigationRejected {
  ok: false;
  reason: string;
}

export type HostNavigationResult =
  | HostNavigationAccepted
  | HostNavigationRejected;

export interface HostReportCreatedEvent {
  reportId: string;
  title?: string;
  version: number;
}

export interface HostEventHandlers {
  /** 处理 iframe 的导航请求：校验通过才调用宿主的导航实现。 */
  navigate(
    request: Pick<BridgeNavigateRequest, 'params' | 'route'>,
  ): HostNavigationResult;
  /** 处理报表事件（只做形状校验后的转发）。 */
  reportCreated(event: BridgeReportCreated): HostReportCreatedEvent;
}

const ROUTE_NAME = /^[a-z][a-z0-9_.-]{0,63}$/u;

/** 校验导航请求：返回可执行的路由与参数，或稳定拒绝原因。 */
export function validateHostNavigation(
  request: { params?: Record<string, unknown>; route: unknown },
  registry: HostRouteRegistry,
): HostNavigationResult {
  if (typeof request.route !== 'string' || !ROUTE_NAME.test(request.route)) {
    return { ok: false, reason: 'ROUTE_INVALID' };
  }
  const definition = registry[request.route];
  if (definition === undefined) {
    return { ok: false, reason: 'ROUTE_NOT_REGISTERED' };
  }
  const allowed = definition.params ?? {};
  const params = request.params ?? {};
  const normalized: Record<string, boolean | number | string> = {};
  for (const [name, value] of Object.entries(params)) {
    const expected = allowed[name];
    if (expected === undefined) {
      return { ok: false, reason: `PARAM_NOT_REGISTERED:${name}` };
    }
    if (
      expected === 'string' &&
      typeof value === 'string' &&
      value.length <= 256
    ) {
      normalized[name] = value;
      continue;
    }
    if (
      expected === 'number' &&
      typeof value === 'number' &&
      Number.isFinite(value)
    ) {
      normalized[name] = value;
      continue;
    }
    if (expected === 'boolean' && typeof value === 'boolean') {
      normalized[name] = value;
      continue;
    }
    return { ok: false, reason: `PARAM_TYPE_MISMATCH:${name}` };
  }
  // 登记为必填的参数缺失：拒绝（由 required 语义决定，宿主自行在 onNavigate 里再判）
  return { ok: true, params: normalized, route: request.route };
}

/** 创建宿主事件处理器（把校验与业务回调绑在一起，避免调用方漏校验）。 */
export function createHostEventHandlers(options: {
  onNavigate?: (event: HostNavigationAccepted) => void;
  onReportCreated?: (event: HostReportCreatedEvent) => void;
  routes: HostRouteRegistry;
}): HostEventHandlers {
  return {
    navigate(request) {
      const result = validateHostNavigation(request, options.routes);
      if (result.ok) {
        options.onNavigate?.(result);
      }
      return result;
    },
    reportCreated(event) {
      const forwarded: HostReportCreatedEvent = {
        reportId: event.reportId,
        version: event.version,
        ...(event.title === undefined ? {} : { title: event.title }),
      };
      options.onReportCreated?.(forwarded);
      return forwarded;
    },
  };
}
