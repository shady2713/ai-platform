import type { IContextMenuItem } from './interface';

import { describe, expect, it, vi } from 'vitest';

// Side-effect import executes the type-only context-menu contract module.
import './interface';

describe('context-menu item contract', () => {
  it('routes click data to the item handler', () => {
    const handler = vi.fn();
    const item: IContextMenuItem = {
      handler,
      key: 'refresh',
      text: 'Refresh',
    };
    const payload = { tab: 'current' };
    item.handler?.(payload);
    expect(handler).toHaveBeenCalledWith(payload);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('describes separator, hidden and disabled entries', () => {
    const separator: IContextMenuItem = {
      key: 'sep',
      separator: true,
      text: '',
    };
    const hidden: IContextMenuItem = { hidden: true, key: 'x', text: 'X' };
    const disabled: IContextMenuItem = {
      disabled: true,
      key: 'y',
      shortcut: 'Ctrl+R',
      text: 'Y',
    };
    expect(separator.separator).toBe(true);
    expect(hidden.hidden).toBe(true);
    expect(disabled.disabled).toBe(true);
    expect(disabled.shortcut).toBe('Ctrl+R');
    expect(separator.handler).toBeUndefined();
  });
});
