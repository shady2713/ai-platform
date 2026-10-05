/**
 * `#/utils/feedback` 的探针桥接：把反馈提示变成**可断言的记录**。
 *
 * <p>页面把"稳定错误文案"交给 `showRequestError` / `showSuccessMessage`
 * （Q11 §4：失败路径必须钉住稳定文案，不得只断言"抛了异常"）。生产实现在浏览器里
 * 弹 element-plus 浮层，探针不做视觉断言（ADR 0046 决策 2），改为把提示落到状态仓，
 * 由 Playwright 读回并**逐位**比对。
 */
import { extractErrorMessage } from '../../../../../apps/web-ele/src/utils/feedback';
import { recordFeedback } from './state';

/** 复用生产错误归一逻辑（剥掉"请求参数不正确："前缀），保证文案口径一致。 */
export function showRequestError(error: unknown, fallbackMessage = '请求失败') {
  recordFeedback('ERROR', extractErrorMessage(error, fallbackMessage));
}

export function showSuccessMessage(message: string) {
  recordFeedback('SUCCESS', message);
}

export function showErrorMessage(message: string) {
  recordFeedback('ERROR', message);
}

export function showWarningMessage(message: string) {
  recordFeedback('ERROR', message);
}

export function showLoadingMessage(): void {
  // 加载提示是瞬时浮层，探针不做视觉断言（ADR 0046 决策 2），不记录。
}

export function showAlertDialog(): Promise<void> {}

/** 二次确认：探针恒定"确认"，让被确认的提交继续走到提交点。 */
export async function showConfirmDialog(): Promise<void> {}
