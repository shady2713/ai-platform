import { describe, expect, it } from 'vitest';

import { formatDecimal, minorUnitsToMajorUnits } from '../formatNumber';

describe('formatDecimal', () => {
  it.each([
    [12, 2, '12.00'],
    ['12.5', 3, '12.500'],
    [' 12 ', 0, '12'],
    [-1.235, 2, '-1.24'],
  ] as const)('formats %s with %s digits', (value, digits, expected) => {
    expect(formatDecimal(value, digits)).toBe(expected);
  });

  it.each([undefined, null, '', ' ', '12invalid', Number.NaN, Infinity])(
    'rejects the invalid value %s',
    (value) => {
      expect(formatDecimal(value)).toBe('');
    },
  );

  it.each([-1, 1.5, 21])('rejects the invalid digit contract %s', (digits) => {
    expect(() => formatDecimal(1, digits)).toThrow(RangeError);
  });
});

describe('minorUnitsToMajorUnits', () => {
  it('converts finite numbers and numeric strings', () => {
    expect(minorUnitsToMajorUnits(1234)).toBe(12.34);
    expect(minorUnitsToMajorUnits('-50')).toBe(-0.5);
  });

  it.each([undefined, null, '', 'invalid', Number.NaN, Infinity])(
    'rejects the invalid value %s',
    (value) => {
      expect(minorUnitsToMajorUnits(value)).toBeUndefined();
    },
  );
});
