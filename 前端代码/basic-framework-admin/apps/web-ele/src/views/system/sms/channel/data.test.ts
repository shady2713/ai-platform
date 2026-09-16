import { describe, expect, it, vi } from 'vitest';

import { useFormSchema, useGridColumns } from './data';

vi.mock('@vben/constants', () => ({
  CommonStatusEnum: { ENABLE: 0 },
  DICT_TYPE: {
    COMMON_STATUS: 'common_status',
    SYSTEM_SMS_CHANNEL_CODE: 'system_sms_channel_code',
  },
}));

vi.mock('@vben/hooks', () => ({
  getDictOptions: vi.fn(() => []),
}));

describe('sms channel credential form', () => {
  it.each(['apiKey', 'apiSecret'])(
    '创建渠道时 %s 必填、编辑时允许留空保留',
    async (fieldName) => {
      const field = useFormSchema().find(
        (item) => item.fieldName === fieldName,
      );
      const rules = field?.dependencies?.rules;

      if (!field || typeof rules !== 'function') {
        throw new TypeError(`${fieldName} field must provide dependency rules`);
      }

      const actions = {} as Parameters<typeof rules>[1];
      const createRule = await rules({ id: undefined }, actions);
      const updateRule = await rules({ id: 1 }, actions);
      if (!hasSafeParse(createRule) || !hasSafeParse(updateRule)) {
        throw new TypeError(
          `${fieldName} dependency rules must return Zod schemas`,
        );
      }

      expect(createRule.safeParse('').success).toBe(false);
      expect(updateRule.safeParse('').success).toBe(true);
    },
  );

  it('列表不展示供应商账号', () => {
    expect(useGridColumns()).not.toContainEqual(
      expect.objectContaining({ field: 'apiKey' }),
    );
  });
});

interface SafeParseRule {
  safeParse: (value: unknown) => { success: boolean };
}

function hasSafeParse(value: unknown): value is SafeParseRule {
  return (
    typeof value === 'object' &&
    value !== null &&
    'safeParse' in value &&
    typeof value.safeParse === 'function'
  );
}
