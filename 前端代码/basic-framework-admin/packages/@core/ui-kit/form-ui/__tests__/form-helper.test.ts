import { describe, expect, it, vi } from 'vitest';
import { z } from 'zod';

import {
  getBaseRules,
  getDefaultValueInZodStack,
  isEventObjectLike,
} from '../src/form-render/helper';

describe('form render helpers', () => {
  it('unwraps optional and effect rules to their base Zod type', () => {
    const baseRule = z.string();

    expect(getBaseRules(baseRule.optional())).toBe(baseRule);
    expect(getBaseRules(baseRule.refine((value) => value.length > 0))).toBe(
      baseRule,
    );
  });

  it('returns null when a rule has no Zod schema', () => {
    expect(getBaseRules(undefined)).toBeNull();
    expect(getBaseRules(null)).toBeNull();
    expect(getBaseRules('required')).toBeNull();
  });

  it('finds a default value through nested Zod wrappers', () => {
    const rule = z.string().default('framework').optional();

    expect(getDefaultValueInZodStack(rule)).toBe('framework');
    expect(getDefaultValueInZodStack(z.string())).toBeUndefined();
    expect(getDefaultValueInZodStack('required')).toBeUndefined();
  });

  it('accepts only event-like objects with a target and handler', () => {
    const event = {
      stopPropagation: vi.fn(),
      target: { value: 'framework' },
    };

    expect(isEventObjectLike(event)).toBe(true);
    expect(isEventObjectLike({ stopPropagation: vi.fn() })).toBe(false);
    expect(isEventObjectLike({ stopPropagation: 'invalid', target: {} })).toBe(
      false,
    );
    expect(isEventObjectLike(null)).toBe(false);
  });
});
