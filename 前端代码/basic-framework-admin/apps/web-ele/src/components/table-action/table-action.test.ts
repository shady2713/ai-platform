import { shallowMount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import TableAction from './table-action.vue';

const { hasAccessByCodes } = vi.hoisted(() => ({
  hasAccessByCodes: vi.fn<(codes: string[]) => boolean>(),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes }),
}));

vi.mock('@vben/icons', () => ({
  IconifyIcon: { name: 'IconifyIcon', template: '<i />' },
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('element-plus', () => ({
  ElButton: { name: 'ElButton' },
  ElDropdown: { name: 'ElDropdown' },
  ElDropdownItem: { name: 'ElDropdownItem' },
  ElDropdownMenu: { name: 'ElDropdownMenu' },
  ElPopconfirm: { name: 'ElPopconfirm' },
  ElSpace: { name: 'ElSpace' },
  ElTooltip: { name: 'ElTooltip' },
}));

describe('table action', () => {
  beforeEach(() => {
    hasAccessByCodes.mockReset();
    hasAccessByCodes.mockReturnValue(true);
  });

  it('renders an allowed action and invokes it exactly once', async () => {
    const onClick = vi.fn();
    const wrapper = shallowMount(TableAction, {
      global: { renderStubDefaultSlot: true },
      props: {
        actions: [{ auth: ['system:user'], label: 'Edit', onClick }],
      },
    });

    expect(wrapper.text()).toContain('Edit');
    expect(hasAccessByCodes).toHaveBeenCalledOnce();

    await wrapper.findComponent({ name: 'ElButton' }).trigger('click');
    expect(onClick).toHaveBeenCalledOnce();
  });

  it('does not render actions denied by permission or business rules', () => {
    hasAccessByCodes.mockReturnValue(false);
    const wrapper = shallowMount(TableAction, {
      global: { renderStubDefaultSlot: true },
      props: {
        actions: [
          { auth: ['system:user'], label: 'Denied' },
          { ifShow: false, label: 'Hidden' },
        ],
      },
    });

    expect(wrapper.text()).not.toContain('Denied');
    expect(wrapper.text()).not.toContain('Hidden');
    expect(hasAccessByCodes).toHaveBeenCalledOnce();
  });

  it('does not bypass a dropdown confirmation through the command event', () => {
    const onClick = vi.fn();
    const confirm = vi.fn();
    const wrapper = shallowMount(TableAction, {
      global: { renderStubDefaultSlot: true },
      props: {
        dropDownActions: [
          {
            label: 'Delete',
            onClick,
            popConfirm: { confirm, title: 'Delete?' },
          },
        ],
      },
    });

    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 0);

    expect(onClick).not.toHaveBeenCalled();
    expect(confirm).not.toHaveBeenCalled();
  });

  it('ignores disabled dropdown commands', () => {
    const onClick = vi.fn();
    const wrapper = shallowMount(TableAction, {
      global: { renderStubDefaultSlot: true },
      props: {
        dropDownActions: [{ disabled: true, label: 'Disabled', onClick }],
      },
    });

    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 0);

    expect(onClick).not.toHaveBeenCalled();
  });
});
