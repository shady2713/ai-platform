import type { BusinessContext } from '@vben/ai-contracts';

import { parseBusinessContext } from '@vben/ai-contracts';

/**
 * 业务上下文仓库（C08）：**只作用下一次运行**。
 *
 * <p>为什么需要一层"待生效"语义：宿主会在浏览过程中不断更新当前页面/对象/筛选条件
 * （FR-13：上下文更新影响下一次 run，不修改在执行 run）。如果直接把最新值传给运行请求，
 * 就会出现在运行中途改变上下文、甚至两个并发运行共享一份可变对象的问题。这里的规则是：
 *
 * <ol>
 *   <li>`update()` 只更新"下一次运行将使用的值"，并对输入做契约校验（非法即拒绝，不静默丢弃字段）；</li>
 *   <li>`snapshot()` 在**运行受理的瞬间**取一份不可变副本，运行全过程使用这份副本；</li>
 *   <li>运行期间再次 `update()` 不影响已取快照的运行，只影响之后新发起的运行。</li>
 * </ol>
 *
 * <p>上下文里没有身份与范围字段（见 `@vben/ai-contracts` 的 `businessContextSchema`）：
 * 辅助信息不参与权限判定。
 */
export interface BusinessContextStore {
  /** 清空待生效上下文（例如宿主切用户/切页面时）。 */
  clear(): void;
  /** 下一次运行将使用的上下文（快照副本）。 */
  current(): BusinessContext | null;
  /** 取快照：运行受理时调用，之后的更新不影响这次运行。 */
  snapshot(): BusinessContext | null;
  /** 更新待生效上下文（校验失败即抛错，保持原值不变）。 */
  update(input: unknown): void;
}

export function createBusinessContextStore(
  initial?: BusinessContext,
): BusinessContextStore {
  let pending: BusinessContext | null =
    initial === undefined ? null : parseBusinessContext(initial);

  return {
    clear(): void {
      pending = null;
    },
    current(): BusinessContext | null {
      return pending === null ? null : { ...pending };
    },
    snapshot(): BusinessContext | null {
      // 运行受理时取副本：调用方持有它，之后的更新只影响后续运行
      return pending === null ? null : structuredClone(pending);
    },
    update(input: unknown): void {
      // 先解析再替换：校验失败时保留原值（不出现"半更新"的上下文）
      pending = parseBusinessContext(input);
    },
  };
}
