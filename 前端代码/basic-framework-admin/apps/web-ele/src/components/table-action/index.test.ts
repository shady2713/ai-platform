import { describe, expect, it, vi } from 'vitest';

import { ACTION_ICON, TableAction } from './index';

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));

vi.mock('@vben/icons', () => ({
  IconifyIcon: { name: 'IconifyIcon' },
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const slotOnly = (name: string) =>
    defineComponent({
      name,
      setup(_props, { slots }) {
        return () => h('div', slots.default?.());
      },
    });
  return {
    ElButton: slotOnly('ElButton'),
    ElDropdown: slotOnly('ElDropdown'),
    ElDropdownItem: slotOnly('ElDropdownItem'),
    ElDropdownMenu: slotOnly('ElDropdownMenu'),
    ElPopconfirm: slotOnly('ElPopconfirm'),
    ElSpace: slotOnly('ElSpace'),
    ElTooltip: slotOnly('ElTooltip'),
  };
});

describe('table-action index contract', () => {
  it('re-exports the TableAction component and the action icon map', () => {
    expect(TableAction).toBeDefined();
    const declaredProps = (TableAction as { props?: object }).props ?? {};
    expect(Object.keys(declaredProps).toSorted()).toEqual([
      'actions',
      'divider',
      'dropDownActions',
    ]);
    expect(ACTION_ICON.ADD).toBe('lucide:plus');
    expect(ACTION_ICON.DELETE).toBe('lucide:trash-2');
    expect(ACTION_ICON.EDIT).toBe('lucide:edit');
  });
});
