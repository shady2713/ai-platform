import { z } from 'zod';

import { resultBlockSchema } from './result-block';

/**
 * 运行事件（SSE）：与 docs/contracts/ai/run-event.schema.json 对齐。
 *
 * seq 由服务端并发安全分配，heartbeat 不是事件；未知 schemaVersion 必须拒绝。
 */
export const runStatusSchema = z.enum([
  'QUEUED',
  'RUNNING',
  'WAITING_INPUT',
  'WAITING_CONFIRMATION',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
]);

export const runEventSchema = z.object({
  schemaVersion: z.literal('1.0'),
  seq: z.number().int().min(1),
  runId: z
    .string()
    .regex(/^run_[\w-]{3,35}$/)
    .max(40),
  status: runStatusSchema,
  block: resultBlockSchema.optional(),
  createdAt: z.string().datetime(),
});

export type RunEvent = z.infer<typeof runEventSchema>;
export type RunStatus = z.infer<typeof runStatusSchema>;

/** 解析运行事件；未知版本、未知状态或多余字段一律拒绝。 */
export function parseRunEvent(input: unknown): RunEvent {
  return runEventSchema.parse(input);
}
