/**
 * `@vben/access` 的探针桥接：**只提供权限码来源，不实现判定语义**。
 *
 * <p>判定语义（"权限码交集非空即放行"）逐字对齐生产实现
 * `packages/effects/access/src/use-access.ts:29`；真正的"某条动作要不要显示"
 * 由生产代码 `apps/web-ele/src/components/table-action/actions.ts:23` 的
 * `isActionVisible` 判定。桥接若自己实现一遍可见性判定，用例就变成自证。
 *
 * <p>为什么需要桥接：真实 `useAccess` 读 Pinia 的 `useAccessStore`，
 * 而承载该 store 的应用外壳需要登录态与后端（Q11 §4 明确排除真实凭据联调）。
 */
import { computed } from 'vue';

import { getState } from './state';

function hasAccessByCodes(codes: string[]): boolean {
  const userCodesSet = new Set(getState().accessCodes);
  const intersection = codes.filter((item) => userCodesSet.has(item));
  return intersection.length > 0;
}

function hasAccessByRoles(roles: string[]): boolean {
  // 探针不模拟角色（AI 页面一律按权限码显隐），恒为无权，保证"角色"不是隐性放行口。
  return roles.length === 0 ? false : false;
}

export function useAccess() {
  return {
    accessMode: computed(() => 'frontend'),
    hasAccessByCodes,
    hasAccessByRoles,
    toggleAccessMode: async () => undefined,
  };
}
