import { z } from '@vben/common-ui';
import { MOBILE_REGEX } from '@vben/utils';

const USERNAME_REGEX = /^[a-z][a-z0-9_]{3,29}$/;
const EMAIL_REGEX = /^[\w.%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i;
const PERCENT_REGEX = /^(?:100(?:\.0{1,2})?|\d{1,2}(?:\.\d{1,2})?)$/;
const NICKNAME_UNSAFE_REGEX = /[\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}]/u;
const PASSWORD_MIN_CODE_POINTS = 15;
const PASSWORD_MAX_UTF8_BYTES = 72;
const UTF_16_SURROGATE_START = 55_296;
const UTF_16_SURROGATE_END = 57_343;
const NICKNAME_MAX_CODE_POINTS = 30;
const REMARK_MAX_CODE_POINTS = 500;

function trimValue(value: unknown) {
  return String(value ?? '').trim();
}

function isBlank(value: unknown) {
  return trimValue(value).length === 0;
}

export function isUsernameValue(value: string) {
  return USERNAME_REGEX.test(normalizeUsernameValue(value));
}

function normalizeUsernameValue(value: string) {
  return value.normalize('NFC').trim().toLowerCase();
}

export function isPasswordValue(value: string) {
  const codePoints = [...value];
  return (
    codePoints.length >= PASSWORD_MIN_CODE_POINTS &&
    new TextEncoder().encode(value).length <= PASSWORD_MAX_UTF8_BYTES &&
    codePoints.every((character) => {
      const codePoint = character.codePointAt(0);
      return (
        codePoint === undefined ||
        codePoint < UTF_16_SURROGATE_START ||
        codePoint > UTF_16_SURROGATE_END
      );
    })
  );
}

export function normalizeNicknameValue(value: string) {
  return value.normalize('NFC').trim();
}

export function isNicknameValue(value: string) {
  const normalized = normalizeNicknameValue(value);
  return (
    normalized.length > 0 &&
    [...normalized].length <= NICKNAME_MAX_CODE_POINTS &&
    !NICKNAME_UNSAFE_REGEX.test(normalized)
  );
}

export function isMobileValue(value: string) {
  return MOBILE_REGEX.test(value.trim());
}

export function isEmailValue(value: string) {
  return EMAIL_REGEX.test(normalizeEmailValue(value));
}

export function isPercentValue(value: number | string) {
  return PERCENT_REGEX.test(String(value));
}

export function isQuantityValue(value: number) {
  return Number.isInteger(value) && value >= 0;
}

export function buildRequiredUsernameSchema(label = '用户名') {
  return z
    .string({ required_error: `请输入${label}` })
    .trim()
    .min(1, { message: `请输入${label}` })
    .transform(normalizeUsernameValue)
    .refine(isUsernameValue, {
      message: `${label}必须以字母开头，并使用 4-30 位小写字母、数字或下划线`,
    });
}

export function buildRequiredPasswordSchema(label = '密码') {
  return z
    .string({ required_error: `请输入${label}` })
    .min(1, { message: `请输入${label}` })
    .refine(isPasswordValue, {
      message: `${label}至少 15 个字符，且 UTF-8 编码不能超过 72 字节`,
    });
}

export function buildLoginPasswordSchema(label = '密码') {
  return z
    .string({ required_error: `请输入${label}` })
    .min(1, { message: `请输入${label}` });
}

export function buildRequiredNicknameSchema(label = '昵称') {
  return z
    .string({ required_error: `请输入${label}` })
    .transform(normalizeNicknameValue)
    .refine(isNicknameValue, {
      message: `${label}需为 1-30 个字符，且不能包含控制字符或换行`,
    });
}

export function buildOptionalMobileSchema(label = '手机号') {
  return z
    .string()
    .optional()
    .transform((value) => normalizeOptionalTrimmedValue(value))
    .refine((value) => value === undefined || isMobileValue(value), {
      message: `${label}格式不正确`,
    });
}

export function buildRequiredMobileSchema(label = '手机号') {
  return z
    .string({ required_error: `请输入${label}` })
    .trim()
    .min(1, { message: `请输入${label}` })
    .refine(isMobileValue, {
      message: `${label}格式不正确`,
    });
}

export function buildOptionalEmailSchema(label = '邮箱') {
  return z
    .string()
    .optional()
    .transform((value) => {
      const normalized = normalizeOptionalTrimmedValue(value);
      return normalized === undefined
        ? undefined
        : normalizeEmailValue(normalized);
    })
    .refine((value) => value === undefined || isEmailValue(value), {
      message: `${label}格式不正确`,
    });
}

export function buildRequiredEmailSchema(label = '邮箱') {
  return z
    .string({ required_error: `请输入${label}` })
    .trim()
    .min(1, { message: `请输入${label}` })
    .transform(normalizeEmailValue)
    .refine(isEmailValue, {
      message: `${label}格式不正确`,
    });
}

export function buildOptionalRemarkSchema(label = '备注') {
  return z
    .string()
    .optional()
    .refine(
      (value) =>
        value === undefined || [...value].length <= REMARK_MAX_CODE_POINTS,
      { message: `${label}长度不能超过 500 个字符` },
    );
}

export function buildOptionalPercentSchema(label = '百分比') {
  return z
    .union([z.string(), z.number()])
    .optional()
    .refine(
      (value) => {
        return (
          value === undefined ||
          value === null ||
          isBlank(value) ||
          isPercentValue(value)
        );
      },
      {
        message: `${label}必须在 0-100 之间，最多保留两位小数`,
      },
    );
}

export function buildRequiredPercentSchema(label = '百分比') {
  return z.union([z.string(), z.number()]).refine(
    (value) => {
      return isPercentValue(value);
    },
    {
      message: `${label}必须在 0-100 之间，最多保留两位小数`,
    },
  );
}

export function buildRequiredQuantitySchema(label = '数量') {
  return z
    .number({ required_error: `请输入${label}` })
    .int(`${label}必须为非负整数`)
    .min(0, `${label}必须为非负整数`);
}

function normalizeEmailValue(value: string) {
  const normalized = value.trim();
  const atIndex = normalized.lastIndexOf('@');
  if (atIndex === -1) {
    return normalized;
  }
  return `${normalized.slice(0, atIndex + 1)}${normalized.slice(atIndex + 1).toLowerCase()}`;
}

function normalizeOptionalTrimmedValue(value: string | undefined) {
  if (value === undefined) {
    return undefined;
  }
  const normalized = value.trim();
  return normalized.length === 0 ? undefined : normalized;
}

// ===== AI 中台公用字段（F08 冻结，与后端 AiFieldRules 逐字一致）=====

// 与后端 AiFieldRules 及 docs/contracts/field-catalog.yaml 逐字一致，不使用 \\w 简写
// eslint-disable-next-line regexp/prefer-w
export const AI_SERVICE_KEY_REGEX = /^svc_[A-Za-z0-9_-]{3,35}$/;

// 与后端 AiFieldRules 及 docs/contracts/field-catalog.yaml 逐字一致，不使用 \\w 简写
// eslint-disable-next-line regexp/prefer-w
export const AI_CONVERSATION_KEY_REGEX = /^conv_[A-Za-z0-9_-]{3,35}$/;

// 与后端 AiFieldRules 及 docs/contracts/field-catalog.yaml 逐字一致，不使用 \\w 简写
// eslint-disable-next-line regexp/prefer-w
export const AI_RUN_KEY_REGEX = /^run_[A-Za-z0-9_-]{3,35}$/;

export const AI_BUSINESS_KEY_MAX_LENGTH = 40;

export const AI_IDEMPOTENCY_KEY_MIN_LENGTH = 16;

export const AI_IDEMPOTENCY_KEY_MAX_LENGTH = 128;

export const AI_MESSAGE_MAX_LENGTH = 16_000;

export function isAiServiceKeyValue(value: string) {
  return (
    value.length <= AI_BUSINESS_KEY_MAX_LENGTH &&
    AI_SERVICE_KEY_REGEX.test(value)
  );
}

export function isAiConversationKeyValue(value: string) {
  return (
    value.length <= AI_BUSINESS_KEY_MAX_LENGTH &&
    AI_CONVERSATION_KEY_REGEX.test(value)
  );
}

export function isAiRunKeyValue(value: string) {
  return (
    value.length <= AI_BUSINESS_KEY_MAX_LENGTH && AI_RUN_KEY_REGEX.test(value)
  );
}

export function isAiIdempotencyKeyValue(value: string) {
  return (
    value.length >= AI_IDEMPOTENCY_KEY_MIN_LENGTH &&
    value.length <= AI_IDEMPOTENCY_KEY_MAX_LENGTH
  );
}

export function isAiMessageValue(value: string) {
  return value.trim().length > 0 && value.length <= AI_MESSAGE_MAX_LENGTH;
}
