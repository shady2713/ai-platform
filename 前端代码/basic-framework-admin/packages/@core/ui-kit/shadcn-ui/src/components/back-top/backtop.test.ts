import type { BacktopProps } from './backtop';

import { describe, expect, it } from 'vitest';

import { backtopProps } from './backtop';

describe('backtopProps contract', () => {
  it('declares the documented defaults', () => {
    expect(backtopProps.bottom.default).toBe(40);
    expect(backtopProps.right.default).toBe(40);
    expect(backtopProps.target.default).toBe('');
    expect(backtopProps.visibilityHeight.default).toBe(200);
  });

  it('declares numeric distances and a string target selector', () => {
    expect(backtopProps.bottom.type).toBe(Number);
    expect(backtopProps.right.type).toBe(Number);
    expect(backtopProps.visibilityHeight.type).toBe(Number);
    expect(backtopProps.target.type).toBe(String);
  });

  it('backtopProps accepts partial overrides only', () => {
    const props: BacktopProps = { bottom: 8, visibilityHeight: 120 };
    expect(props.bottom).toBe(8);
    expect(props.visibilityHeight).toBe(120);
    expect(props.right).toBeUndefined();
    expect(props.target).toBeUndefined();
  });
});
