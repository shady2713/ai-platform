import { describe, expect, it, vi } from 'vitest';

import { useDetailSchema } from './data';

vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn() }));
vi.mock('#/components/dict-tag', () => ({ DictTag: {} }));
vi.mock('#/utils', () => ({ getRangePickerDefaultProps: () => ({}) }));

describe('operate log detail schema', () => {
  it.each(['traceId', 'extra'])(
    'shows %s only when it has a value',
    (field) => {
      const schema = useDetailSchema().find((item) => item.field === field);

      expect(schema?.show?.({ [field]: 'present' })).toBe(true);
      expect(schema?.show?.({ [field]: '' })).toBe(false);
      expect(schema?.show?.({})).toBe(false);
    },
  );
});
