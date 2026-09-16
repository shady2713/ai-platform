import type { FormRuleHandler } from '@vben/common-ui';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { initSetupVbenForm } from './form';

const { setupVbenFormMock } = vi.hoisted(() => ({
  setupVbenFormMock: vi.fn(),
}));

vi.mock('@vben/common-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@vben/common-ui')>();
  return {
    ...actual,
    setupVbenForm: setupVbenFormMock,
  };
});

vi.mock('@vben/locales', () => ({
  $t: (key: string, params?: unknown[]) =>
    params?.length ? `${key}:${params[0]}` : key,
}));

type RuleName =
  | 'email'
  | 'emailRequired'
  | 'mobile'
  | 'mobileRequired'
  | 'password'
  | 'passwordRequired'
  | 'percent'
  | 'quantity'
  | 'required'
  | 'selectRequired'
  | 'username'
  | 'usernameRequired';

type FormSetup = {
  config: {
    modelPropNameMap: Record<string, string>;
  };
  defineRules: Record<RuleName, FormRuleHandler>;
};

describe('form adapter', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('registers model bindings and every framework validation rule', async () => {
    await initSetupVbenForm();

    expect(setupVbenFormMock).toHaveBeenCalledOnce();
    const setup = setupVbenFormMock.mock.calls[0]?.[0] as FormSetup;
    expect(setup.config.modelPropNameMap).toEqual({
      CheckboxGroup: 'model-value',
      Upload: 'fileList',
    });
    expect(Object.keys(setup.defineRules).toSorted()).toEqual([
      'email',
      'emailRequired',
      'mobile',
      'mobileRequired',
      'password',
      'passwordRequired',
      'percent',
      'quantity',
      'required',
      'selectRequired',
      'username',
      'usernameRequired',
    ]);
  });

  it('distinguishes text-required and selection-required empty values', async () => {
    const rules = await registeredRules();
    const context = { label: '字段' };

    expect(rules.required('', undefined, context)).toBe(
      'ui.formRules.required:字段',
    );
    expect(rules.required(null, undefined, context)).toBe(
      'ui.formRules.required:字段',
    );
    expect(rules.required(0, undefined, context)).toBe(true);
    expect(rules.selectRequired(undefined, undefined, context)).toBe(
      'ui.formRules.selectRequired:字段',
    );
    expect(rules.selectRequired('', undefined, context)).toBe(true);
  });

  it.each([
    ['username', '', true],
    ['username', 'valid_name', true],
    ['username', '1bad', '字段必须为 4-30 位字母或数字'],
    ['usernameRequired', '', 'ui.formRules.required:字段'],
    ['usernameRequired', 'valid_name', true],
    ['password', '', true],
    ['password', '123456789012345', true],
    ['password', 'short', '字段至少 15 个字符，且 UTF-8 编码不能超过 72 字节'],
    ['passwordRequired', '', 'ui.formRules.required:字段'],
    ['passwordRequired', '123456789012345', true],
    ['mobile', '', true],
    ['mobile', '13800138000', true],
    ['mobile', '123', 'ui.formRules.mobile:字段'],
    ['mobileRequired', '', 'ui.formRules.required:字段'],
    ['mobileRequired', '13800138000', true],
    ['email', '', true],
    ['email', 'user@example.com', true],
    ['email', 'invalid', '字段格式不正确'],
    ['emailRequired', '', 'ui.formRules.required:字段'],
    ['emailRequired', 'user@example.com', true],
    ['percent', '', true],
    ['percent', '100.00', true],
    ['percent', '100.01', '字段必须在 0-100 之间，最多保留两位小数'],
    ['quantity', '', true],
    ['quantity', 0, true],
    ['quantity', -1, '字段必须为非负整数'],
    ['quantity', 1.5, '字段必须为非负整数'],
  ] as const)(
    'validates %s with value %j',
    async (ruleName, value, expected) => {
      const rules = await registeredRules();

      expect(rules[ruleName](value, undefined, { label: '字段' })).toBe(
        expected,
      );
    },
  );
});

async function registeredRules() {
  await initSetupVbenForm();
  const setup = setupVbenFormMock.mock.calls.at(-1)?.[0] as FormSetup;
  return setup.defineRules;
}
