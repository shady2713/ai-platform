import { z } from 'zod';

/**
 * 自有图表契约（ChartSpec）。
 *
 * <p>ChartSpec 是平台对外的长期协议：模型与报表保存的是本契约，而不是任何厂商 DSL。
 * 渲染适配器（如 G2）只负责把 ChartSpec 转成图表实例，厂商类型不出适配器边界。
 */
export const chartTypeSchema = z.enum(['line', 'bar', 'pie']);

/**
 * 图表数值：number 或十进制字符串。金额必须用字符串（见 docs/contracts/ai/README.md），
 * 存储与传输保留原串，渲染层再做显示用转换。
 */
export const chartValueSchema = z.union([
  z.number().finite(),
  z
    .string()
    .regex(/^-?\d+(\.\d+)?$/, '十进制字符串必须是可选负号加数字，允许小数点')
    .max(64),
]);

export const chartSeriesSchema = z.object({
  name: z.string().min(1).max(64),
  data: z.array(chartValueSchema),
});

export const chartSpecSchema = z
  .object({
    type: chartTypeSchema,
    title: z.string().max(200).optional(),
    categories: z.array(z.string().max(128)).min(1).max(500),
    series: z.array(chartSeriesSchema).min(1).max(20),
  })
  .refine(
    (spec) =>
      spec.series.every(
        (series) => series.data.length === spec.categories.length,
      ),
    { message: '每个系列的 data 长度必须与 categories 长度一致' },
  );

export type ChartSpec = z.infer<typeof chartSpecSchema>;

export type ChartType = z.infer<typeof chartTypeSchema>;

export type ChartValue = z.infer<typeof chartValueSchema>;

/**
 * 解析并校验 ChartSpec；不合法输入抛 ZodError，调用方不得把未校验数据直接交给渲染器。
 */
export function parseChartSpec(input: unknown): ChartSpec {
  return chartSpecSchema.parse(input);
}

/**
 * 安全解析：失败返回 null，用于"渲染前降级为文本"的场景。
 */
export function safeParseChartSpec(input: unknown): ChartSpec | null {
  const result = chartSpecSchema.safeParse(input);
  return result.success ? result.data : null;
}
