import { ref } from 'vue';

import { describe, expect, it } from 'vitest';

import {
  createCustomValidation,
  createRequiredValidation,
  createValidationClassName,
} from './validation';

interface Row {
  count?: unknown;
  enabled?: unknown;
  name?: unknown;
  tags?: unknown;
}

const missingRequiredCases: Array<[string, keyof Row & string, Row]> = [
  ['undefined', 'name', {}],
  ['null', 'name', { name: null }],
  ['blank string', 'name', { name: '   ' }],
  ['not-a-number', 'count', { count: Number.NaN }],
  ['empty array', 'tags', { tags: [] }],
];

const presentRequiredCases: Array<[string, keyof Row & string, Row]> = [
  ['zero', 'count', { count: 0 }],
  ['boolean false', 'enabled', { enabled: false }],
  ['nonblank string', 'name', { name: 'value' }],
  ['nonempty array', 'tags', { tags: ['value'] }],
];

describe('vXE validation callbacks', () => {
  it('does not mark cells before validation is activated', () => {
    const callback = createRequiredValidation<Row>(undefined, 'name');

    expect(callback({ row: {} })).toBe('');
  });

  it.each(missingRequiredCases)(
    'rejects a missing required value: %s',
    (_case, field, row) => {
      const callback = createRequiredValidation<Row>(ref(true), field);

      expect(callback({ row })).toBe('required-field-error');
    },
  );

  it.each(presentRequiredCases)(
    'accepts a present required value: %s',
    (_case, field, row) => {
      const callback = createRequiredValidation<Row>(ref(true), field);

      expect(callback({ row })).toBe('');
    },
  );

  it('supports explicit business validation without field-name rules', () => {
    const positiveCount = createValidationClassName(
      ref(true),
      'count',
      (row: Readonly<Row>) => typeof row.count === 'number' && row.count > 0,
    );
    const named = createCustomValidation<Row>(
      ref(true),
      (row) => row.name === 'allowed',
    );
    const inactive = createCustomValidation<Row>(ref(false), () => false);

    expect(positiveCount({ row: { count: 0 } })).toBe('required-field-error');
    expect(positiveCount({ row: { count: 1 } })).toBe('');
    expect(named({ row: { name: 'denied' } })).toBe('required-field-error');
    expect(named({ row: { name: 'allowed' } })).toBe('');
    expect(inactive({ row: {} })).toBe('');
  });
});
