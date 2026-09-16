import { describe, expect, it } from 'vitest';

import { isMobile } from '../validator';

describe('isMobile', () => {
  it.each([
    ['missing value', undefined, false],
    ['null value', null, false],
    ['empty value', '', false],
    ['valid mainland mobile', '13800138000', true],
    ['invalid prefix', '12800138000', false],
    ['invalid length', '1380013800', false],
  ])('%s', (_case, value, expected) => {
    expect(isMobile(value)).toBe(expected);
  });
});
