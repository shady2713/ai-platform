import type { VbenDropdownMenuItem } from './interface';

import { mount } from '@vue/test-utils';
import { markRaw } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import DropdownMenu from './dropdown-menu.vue';

vi.mock('../../ui', () => ({
  DropdownMenu: {
    name: 'DropdownMenuStub',
    template: '<div class="dd-menu"><slot /></div>',
  },
  DropdownMenuContent: {
    name: 'DropdownMenuContentStub',
    template: '<div class="dd-content"><slot /></div>',
  },
  DropdownMenuGroup: {
    name: 'DropdownMenuGroupStub',
    template: '<div class="dd-group"><slot /></div>',
  },
  DropdownMenuItem: {
    name: 'DropdownMenuItemStub',
    props: ['disabled'],
    template: '<div class="dd-item" :data-disabled="disabled"><slot /></div>',
  },
  DropdownMenuSeparator: {
    name: 'DropdownMenuSeparatorStub',
    template: '<hr class="dd-separator" />',
  },
  DropdownMenuTrigger: {
    name: 'DropdownMenuTriggerStub',
    template: '<div class="dd-trigger"><slot /></div>',
  },
}));

function iconStub() {
  return markRaw({
    name: 'MenuIconStub',
    template: '<span class="menu-icon-stub" />',
  });
}

function mountDropdown(menus: VbenDropdownMenuItem[]) {
  return mount(DropdownMenu, {
    props: { menus },
    slots: { default: '<button class="trigger-slot">open</button>' },
  });
}

describe('vbenDropdownMenu', () => {
  it('renders the trigger slot and the menu labels', () => {
    const wrapper = mountDropdown([
      { label: 'Profile', value: 'profile' },
      { label: 'Settings', value: 'settings' },
    ]);

    expect(wrapper.get('.dd-trigger').text()).toContain('open');
    expect(wrapper.find('.dd-menu').exists()).toBe(true);
    const items = wrapper.findAll('.dd-item');
    expect(items).toHaveLength(2);
    expect(items[0]?.text()).toBe('Profile');
    expect(items[1]?.text()).toBe('Settings');
  });

  it('renders item icons and separators next to the matching entries', () => {
    const wrapper = mountDropdown([
      { icon: iconStub(), label: 'Edit', value: 'edit' },
      { label: 'Priority', separator: true, value: 'priority' },
      { label: 'Quit', value: 'quit' },
    ]);

    const items = wrapper.findAll('.dd-item');
    expect(items[0]?.find('.menu-icon-stub').exists()).toBe(true);
    expect(items[1]?.find('.menu-icon-stub').exists()).toBe(false);
    const separators = wrapper.findAll('.dd-separator');
    expect(separators).toHaveLength(1);
    expect(separators[0]?.attributes('class')).toContain('bg-border');
  });

  it('does not trigger the handler for disabled entries', async () => {
    const handler = vi.fn();
    const wrapper = mountDropdown([
      { disabled: true, label: 'Locked', value: 'locked', handler },
    ]);

    await wrapper.get('.dd-item').trigger('click');
    expect(handler).not.toHaveBeenCalled();
  });

  it('triggers the handler with the component props for enabled entries', async () => {
    const handler = vi.fn();
    const menus = [{ label: 'Preview', value: 'preview', handler }];
    const wrapper = mountDropdown(menus);

    await wrapper.get('.dd-item').trigger('click');
    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledWith(expect.objectContaining({ menus }));
  });
});
