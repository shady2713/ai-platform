/**
 * Q11 管理端页面浏览器验收探针的**可观测状态仓**。
 *
 * <p>它存在的唯一理由是"让安全语义在真实浏览器里可被操纵与观测"：权限码集合、列表行、
 * 反馈提示都由 Playwright 侧通过 `__q11` 写入，再由**生产页面组件**读出并渲染，
 * 最后由 Playwright 在真实 DOM 上断言。因此每条用例都有对照：
 * 同一段生产代码 + 同一份夹具，只改权限码，DOM 就必须跟着变。
 *
 * <p>纪律：本目录**只读引用**产品代码，不复制任何渲染/判定逻辑。
 * `hasAccessByCodes` 的"交集非空即放行"语义逐字对齐
 * `packages/effects/access/src/use-access.ts:29`，不是探针自创的判定。
 */

/** 探针捕获的反馈提示（对应 `#/utils/feedback` 的成功/失败通道）。 */
export interface FeedbackRecord {
  kind: 'ERROR' | 'SUCCESS';
  message: string;
}

/** 探针捕获的 API 调用（`args` 只用于断言"提交了什么事实"）。 */
export interface ApiCallRecord {
  args: unknown;
  name: string;
}

interface ProbeState {
  /** 登录主体持有的权限码；`hasAccessByCodes` 读它。 */
  accessCodes: string[];
  /** 列表行夹具；网格桥接渲染它。 */
  rows: Array<Record<string, unknown>>;
  /** 捕获到的反馈提示，按发生顺序追加。 */
  feedback: FeedbackRecord[];
  /** 捕获到的 API 调用，按发生顺序追加。 */
  apiCalls: ApiCallRecord[];
  /** 捕获到的未捕获错误（window error / Promise 拒绝 / Vue errorHandler）。 */
  errors: string[];
  /** 已完成的列表查询次数：Playwright 用它做确定性等待（不靠 sleep）。 */
  gridLoads: number;
  /** 弹窗当前是否打开（`useVbenModal` 桥接的可见状态）。 */
  modalOpen: boolean;
  /** 已注册的弹窗实例序号，用于给容器一个稳定标识。 */
  modalSeq: number;
}

const state: ProbeState = {
  accessCodes: [],
  apiCalls: [],
  errors: [],
  feedback: [],
  gridLoads: 0,
  modalOpen: false,
  modalSeq: 0,
  rows: [],
};

export function getState(): ProbeState {
  return state;
}

export function resetState(): void {
  state.accessCodes = [];
  state.apiCalls = [];
  state.errors = [];
  state.feedback = [];
  state.gridLoads = 0;
  state.modalOpen = false;
  state.modalSeq = 0;
  state.rows = [];
}

/** 网格完成一次列表查询后自增，供 Playwright 确定性等待。 */
export function recordGridLoad(): void {
  state.gridLoads += 1;
}

export function recordApiCall(name: string, args: unknown): void {
  state.apiCalls.push({ args, name });
}

export function recordError(message: string): void {
  state.errors.push(message);
}

export function recordFeedback(
  kind: FeedbackRecord['kind'],
  message: string,
): void {
  state.feedback.push({ kind, message });
}

/** 写权限码：探针唯一的"登录态"注入口。 */
export function setAccessCodes(codes: string[]): void {
  state.accessCodes = [...codes];
}

/** 写列表行：形状对齐各自的 `Ai*Api` 实体，**不含**任何界面逻辑。 */
export function setRows(rows: Array<Record<string, unknown>>): void {
  state.rows = rows.map((row) => ({ ...row }));
}
