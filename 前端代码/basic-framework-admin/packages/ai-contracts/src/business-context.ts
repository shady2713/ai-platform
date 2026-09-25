import { z } from 'zod';

/**
 * 业务上下文协议（C08）：宿主可传字段的**逐键形状**。
 *
 * <p>与后端 `AiBusinessContextSchema` 同值（那边是权威校验，这里是消费者侧的同一套规则）：
 * 键必须是 FR-13 的六个之一，取值形状逐键收口。**身份与范围字段不在协议内**——
 * 上下文只能作为辅助信息，不能改变应用、主体与授权范围。
 */

const SAFE_TEXT = /^[\p{L}\p{N} _.:/@-]{1,128}$/u;

const LOWERCASE_IDENTIFIER = /^[a-z][a-z0-9_-]{0,63}$/u;

const LOCALE = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/u;

const TIMEZONE = /^[\w+-]{1,32}(?:\/[\w+-]{1,32})*(?::\d{2})?$/u;

/** 筛选条件取值：标量或标量数组（嵌套对象不是筛选条件）。 */
const filterScalar = z.union([
  z.string().max(256),
  z.number().finite(),
  z.boolean(),
]);

export const businessContextSchema = z
  .object({
    filters: z
      .record(
        z.string().regex(LOWERCASE_IDENTIFIER, '筛选字段名必须是小写标识符'),
        z.union([filterScalar, z.array(filterScalar).max(50)]),
      )
      .refine((value) => Object.keys(value).length <= 20, '筛选条件最多 20 项')
      .optional(),
    locale: z.string().max(32).regex(LOCALE, '语言标签形如 zh-CN').optional(),
    objectId: z.string().min(1).max(128).optional(),
    objectType: z
      .string()
      .regex(LOWERCASE_IDENTIFIER, '对象类型必须是小写标识符')
      .optional(),
    page: z.string().regex(SAFE_TEXT, '页面标识只允许常规字符').optional(),
    timezone: z
      .string()
      .max(64)
      .regex(TIMEZONE, '时区形如 Asia/Shanghai 或 +08:00')
      .optional(),
  })
  .strict();

export type BusinessContext = z.infer<typeof businessContextSchema>;

/** 解析业务上下文；未知键与非法形状一律拒绝（不做"去掉坏字段继续用"）。 */
export function parseBusinessContext(input: unknown): BusinessContext {
  return businessContextSchema.parse(input);
}

/** 安全解析：失败返回 null（用于渲染/展示路径，不用于提交给运行）。 */
export function safeParseBusinessContext(
  input: unknown,
): BusinessContext | null {
  const parsed = businessContextSchema.safeParse(input);
  return parsed.success ? parsed.data : null;
}
