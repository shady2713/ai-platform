import type { DrawerProps, DrawerState } from '../drawer';

import { describe, expect, it } from 'vitest';

// Side-effect import executes the type-only drawer contract module.
import '../drawer';

describe('drawer contract types', () => {
  it('drawerState shape flows from DrawerProps defaults', () => {
    const state: DrawerState = { isOpen: false };
    const props: DrawerProps = { placement: 'right', title: 't' };
    expect(state.isOpen).toBe(false);
    expect(props.placement).toBe('right');
    expect(props.title).toBe('t');
  });
});
