import { describe, expect, it } from 'vitest';

import {
  AI_CONVERSATION_KEY_REGEX,
  AI_RUN_KEY_REGEX,
  AI_SERVICE_KEY_REGEX,
  buildLoginPasswordSchema,
  buildOptionalEmailSchema,
  buildOptionalMobileSchema,
  buildOptionalPercentSchema,
  buildOptionalRemarkSchema,
  buildRequiredEmailSchema,
  buildRequiredMobileSchema,
  buildRequiredNicknameSchema,
  buildRequiredPasswordSchema,
  buildRequiredPercentSchema,
  buildRequiredQuantitySchema,
  buildRequiredUsernameSchema,
  isAiConversationKeyValue,
  isAiIdempotencyKeyValue,
  isAiMessageValue,
  isAiRunKeyValue,
  isAiServiceKeyValue,
  isEmailValue,
  isMobileValue,
  isNicknameValue,
  isPasswordValue,
  isPercentValue,
  isQuantityValue,
  isUsernameValue,
} from './field-rules';

describe('field rules', () => {
  it('validates username', () => {
    expect(isUsernameValue('User123')).toBe(true);
    expect(isUsernameValue('abc')).toBe(false);
    expect(isUsernameValue('user_123')).toBe(true);
    expect(isUsernameValue('1user')).toBe(false);
    expect(buildRequiredUsernameSchema().parse('  User_123  ')).toBe(
      'user_123',
    );
  });

  it('validates password', () => {
    expect(isPasswordValue('correct horse battery staple')).toBe(true);
    expect(isPasswordValue('LongPassword123!')).toBe(true);
    expect(isPasswordValue('short-password')).toBe(false);
    expect(isPasswordValue('a'.repeat(73))).toBe(false);
    expect(isPasswordValue('密'.repeat(25))).toBe(false);
    expect(isPasswordValue('\uD800'.repeat(15))).toBe(false);
  });

  it('does not trim passwords', () => {
    const password = '  long password phrase  ';

    expect(buildRequiredPasswordSchema().parse(password)).toBe(password);
    expect(buildLoginPasswordSchema().parse(password)).toBe(password);
  });

  it('normalizes and validates nickname', () => {
    expect(isNicknameValue(' 管理员😀 ')).toBe(true);
    expect(isNicknameValue('a\nb')).toBe(false);
    expect(isNicknameValue('x'.repeat(31))).toBe(false);
    expect(isNicknameValue('   ')).toBe(false);
    expect(buildRequiredNicknameSchema().parse('  Alice  ')).toBe('Alice');
    expect(buildRequiredNicknameSchema().safeParse('Alice\nBob').success).toBe(
      false,
    );
  });

  it('limits remarks by Unicode code points', () => {
    const schema = buildOptionalRemarkSchema();

    expect(schema.safeParse('😀'.repeat(500)).success).toBe(true);
    expect(schema.safeParse('😀'.repeat(501)).success).toBe(false);
    expect(schema.safeParse(undefined).success).toBe(true);
  });

  it('validates mobile', () => {
    expect(isMobileValue('13812345678')).toBe(true);
    expect(isMobileValue('+8613812345678')).toBe(false);
    expect(isMobileValue('23812345678')).toBe(false);
    expect(buildOptionalMobileSchema().parse(' 13812345678 ')).toBe(
      '13812345678',
    );
    expect(buildOptionalMobileSchema().parse('   ')).toBeUndefined();
    expect(buildOptionalMobileSchema().parse(undefined)).toBeUndefined();
    expect(buildRequiredMobileSchema().safeParse('23812345678').success).toBe(
      false,
    );
    expect(buildRequiredMobileSchema().safeParse('   ').success).toBe(false);
  });

  it('validates email', () => {
    expect(isEmailValue('user@example.com')).toBe(true);
    expect(isEmailValue('user@invalid')).toBe(false);
    expect(buildOptionalEmailSchema().parse(' User@EXAMPLE.COM ')).toBe(
      'User@example.com',
    );
    expect(buildOptionalEmailSchema().parse('   ')).toBeUndefined();
    expect(buildOptionalEmailSchema().parse(undefined)).toBeUndefined();
    expect(buildRequiredEmailSchema().parse(' User@EXAMPLE.COM ')).toBe(
      'User@example.com',
    );
    expect(buildRequiredEmailSchema().safeParse('user@invalid').success).toBe(
      false,
    );
  });

  it('validates percent', () => {
    expect(isPercentValue('100')).toBe(true);
    expect(isPercentValue('12.34')).toBe(true);
    expect(isPercentValue('100.001')).toBe(false);
    expect(isPercentValue('-1')).toBe(false);
    expect(buildRequiredPercentSchema().safeParse('').success).toBe(false);
    expect(buildRequiredPercentSchema().safeParse(undefined).success).toBe(
      false,
    );
    expect(buildRequiredPercentSchema().parse(99.99)).toBe(99.99);
  });

  it('allows blank optional percent values', () => {
    const schema = buildOptionalPercentSchema();

    expect(schema.safeParse(undefined).success).toBe(true);
    expect(schema.safeParse('').success).toBe(true);
    expect(schema.safeParse('12.34').success).toBe(true);
    expect(schema.safeParse('100.001').success).toBe(false);
  });
  it('normalizes emails without an at sign before rejecting them', () => {
    const schema = buildOptionalEmailSchema();

    expect(schema.safeParse('not-an-email').success).toBe(false);
    expect(schema.safeParse('not-an-email').error?.issues[0]?.message).toBe(
      '邮箱格式不正确',
    );
  });
  it('validates quantity', () => {
    expect(isQuantityValue(0)).toBe(true);
    expect(isQuantityValue(12)).toBe(true);
    expect(isQuantityValue(-1)).toBe(false);
    expect(isQuantityValue(1.2)).toBe(false);
    expect(buildRequiredQuantitySchema().parse(0)).toBe(0);
    expect(buildRequiredQuantitySchema().safeParse(1.2).success).toBe(false);
    expect(buildRequiredQuantitySchema().safeParse(-1).success).toBe(false);
  });

  it('rejects missing required identity fields', () => {
    expect(buildRequiredUsernameSchema().safeParse(undefined).success).toBe(
      false,
    );
    expect(buildRequiredUsernameSchema().safeParse('abc').success).toBe(false);
    expect(buildRequiredPasswordSchema().safeParse('').success).toBe(false);
    expect(buildLoginPasswordSchema().safeParse(undefined).success).toBe(false);
  });
});

describe('aI 公用字段规则', () => {
  it('业务键前缀与长度必须同时满足', () => {
    expect(isAiServiceKeyValue('svc_demo1')).toBe(true);
    expect(isAiServiceKeyValue('demo1')).toBe(false);
    expect(isAiServiceKeyValue(`svc_${'x'.repeat(36)}`)).toBe(false);
    expect(isAiConversationKeyValue('conv_abc')).toBe(true);
    expect(isAiConversationKeyValue('run_abc')).toBe(false);
    expect(isAiRunKeyValue('run_abc')).toBe(true);
    expect(isAiRunKeyValue('run')).toBe(false);
  });

  it('幂等键与消息长度按冻结区间校验', () => {
    expect(isAiIdempotencyKeyValue('0123456789abcdef')).toBe(true);
    expect(isAiIdempotencyKeyValue('short')).toBe(false);
    expect(isAiIdempotencyKeyValue('x'.repeat(129))).toBe(false);
    expect(isAiMessageValue('你好')).toBe(true);
    expect(isAiMessageValue('   ')).toBe(false);
    expect(isAiMessageValue('x'.repeat(16_001))).toBe(false);
  });

  it('正则与后端 AiFieldRules 登记值一致', () => {
    expect(AI_SERVICE_KEY_REGEX.source).toBe('^svc_[A-Za-z0-9_-]{3,35}$');
    expect(AI_CONVERSATION_KEY_REGEX.source).toBe('^conv_[A-Za-z0-9_-]{3,35}$');
    expect(AI_RUN_KEY_REGEX.source).toBe('^run_[A-Za-z0-9_-]{3,35}$');
  });
});
