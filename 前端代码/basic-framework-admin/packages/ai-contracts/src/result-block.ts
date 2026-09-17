import { z } from 'zod';

import { chartSpecSchema } from './chart-spec';

/**
 * 结果块契约（ResultBlock）：Chat 与报表共享的最小渲染单元。
 *
 * <p>判别联合保证渲染器按 kind 分支处理，未知 kind 在解析期拒绝，
 * 不允许渲染器对好奇数据"尽力而为"，避免注入面。
 */
export const resultBlockSchema = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('text'), text: z.string().max(20_000) }),
  z.object({ kind: z.literal('chart'), spec: chartSpecSchema }),
  z.object({ kind: z.literal('error'), message: z.string().max(1000) }),
]);

export type ResultBlock = z.infer<typeof resultBlockSchema>;

export const resultBlockListSchema = z.array(resultBlockSchema).max(50);

/**
 * 解析结果块列表；任何一块不合法即整体拒绝，避免"部分渲染导致语义歧义"。
 */
export function parseResultBlocks(input: unknown): ResultBlock[] {
  return resultBlockListSchema.parse(input);
}
