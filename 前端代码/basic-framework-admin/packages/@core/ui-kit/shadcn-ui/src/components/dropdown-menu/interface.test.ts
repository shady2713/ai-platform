import type { DropdownMenuProps, VbenDropdownMenuItem } from './interface';

import { describe, expect, it, vi } from 'vitest';

// Side-effect import executes the type-only dropdown-menu contract module.
import './interface';

describe('dropdown-menu item contract', () => {
  it('routes click data to the item handler', () => {
    const handler = vi.fn();
    const item: VbenDropdownMenuItem = {
      handler,
      label: 'Profile',
      value: 'profile',
    };
    const payload = { userId: 7 };
    item.handler?.(payload);
    expect(handler).toHaveBeenCalledWith(payload);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('groups entries into DropdownMenuProps menus', () => {
    const menus: VbenDropdownMenuItem[] = [
      { label: 'Profile', value: 'profile' },
      { separator: true, label: '', value: 'sep' },
      { disabled: true, label: 'Disabled', value: 'disabled' },
    ];
    const props: DropdownMenuProps = { menus };
    expect(props.menus).toHaveLength(3);
    expect(props.menus[1]?.separator).toBe(true);
    expect(props.menus[2]?.disabled).toBe(true);
    expect(props.menus[0]?.handler).toBeUndefined();
  });
});
