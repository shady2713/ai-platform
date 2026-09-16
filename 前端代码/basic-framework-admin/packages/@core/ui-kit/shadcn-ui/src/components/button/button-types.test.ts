import type {
  ValueType,
  VbenButtonGroupProps,
  VbenButtonProps,
} from './button';

import { describe, expect, it, vi } from 'vitest';

// Side-effect import executes the type-only button contract module.
import './button';

describe('button contract types', () => {
  it('vbenButtonProps carries variant, size and state flags', () => {
    const props: VbenButtonProps = {
      as: 'button',
      disabled: true,
      loading: false,
      size: 'sm',
      variant: 'outline',
    };
    expect(props.as).toBe('button');
    expect(props.variant).toBe('outline');
    expect(props.size).toBe('sm');
    expect(props.disabled).toBe(true);
    expect(props.loading).toBe(false);
  });

  it('vbenButtonGroupProps beforeChange gates value changes', async () => {
    const beforeChange = vi.fn(
      (value: ValueType, isChecked: boolean) => value !== 'locked' && isChecked,
    );
    const props: VbenButtonGroupProps = {
      allowClear: true,
      beforeChange,
      gap: 4,
      maxCount: 2,
      multiple: true,
      options: [{ label: 'Alpha', value: 'a' }],
      size: 'middle',
    };
    expect(await props.beforeChange?.('a', true)).toBe(true);
    expect(await props.beforeChange?.('locked', true)).toBe(false);
    expect(beforeChange).toHaveBeenCalledTimes(2);
    expect(props.options?.[0]?.label).toBe('Alpha');
    expect(props.maxCount).toBe(2);
  });
});
