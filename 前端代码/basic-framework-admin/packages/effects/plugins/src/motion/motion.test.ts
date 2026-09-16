import type { MotionPreset } from './types';

import { describe, expect, it } from 'vitest';

import {
  Motion,
  MotionDirective,
  MotionGroup,
  MotionPlugin,
  MotionPresets,
} from './index';

describe('motion plugin barrel', () => {
  it('re-exports the vueuse motion building blocks', () => {
    expect(Motion).toBeDefined();
    expect(MotionGroup).toBeDefined();
    expect(MotionDirective).toBeDefined();
    expect(MotionPlugin).toHaveProperty('install');
  });

  it('lists every supported preset exactly once', () => {
    expect(MotionPresets).toHaveLength(22);
    expect(new Set(MotionPresets).size).toBe(MotionPresets.length);
    expect(MotionPresets).toContain('fade');
    expect(MotionPresets).toContain('popVisibleOnce');
    expect(MotionPresets).toContain('rollBottom');
    expect(MotionPresets).toContain('slideVisibleTop');
  });

  it('preset names satisfy the MotionPreset contract', () => {
    const preset: MotionPreset = 'pop';
    expect(MotionPresets).toContain(preset);
  });
});
