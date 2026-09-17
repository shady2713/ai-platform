import { z } from 'zod';

/**
 * 嵌入与报表共用的主题契约（Theme）。
 *
 * <p>主题只包含设计 token，不包含任何行为或样式实现；宿主通过它让独立 Chat 与后台观感一致。
 * token 值必须来自平台调色板校验，不接受任意 CSS 文本，避免主题成为注入面。
 */
const colorSchema = z
  .string()
  .regex(
    /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i,
    '颜色必须是三位或六位十六进制，例如 #1677ff',
  );

export const themeSchema = z.object({
  primaryColor: colorSchema,
  radius: z.number().min(0).max(24),
  fontFamily: z.string().min(1).max(120),
  colorScheme: z.enum(['light', 'dark']).optional(),
});

export type Theme = z.infer<typeof themeSchema>;

export const defaultTheme: Theme = {
  primaryColor: '#1677ff',
  radius: 6,
  fontFamily:
    'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif',
};

/**
 * 解析主题；缺省字段回落到 defaultTheme，非法值直接拒绝（不静默兜底成任意值）。
 */
export function parseTheme(input: unknown): Theme {
  return themeSchema.parse({ ...defaultTheme, ...(input as object) });
}
