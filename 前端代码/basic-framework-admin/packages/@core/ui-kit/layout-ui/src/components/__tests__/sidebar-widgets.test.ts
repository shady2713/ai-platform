import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import SidebarCollapseButton from '../widgets/sidebar-collapse-button.vue';
import SidebarFixedButton from '../widgets/sidebar-fixed-button.vue';

// Side-effect imports execute the widgets and layout barrels.
import '../../index';
import '../widgets/index';
import '../index';

describe('sidebarCollapseButton', () => {
  it('renders the clickable button', () => {
    const wrapper = mount(SidebarCollapseButton, {
      props: { collapsed: false },
    });
    expect(wrapper.find('.flex-center').exists()).toBe(true);
  });

  it('toggles the collapsed model on click', async () => {
    const wrapper = mount(SidebarCollapseButton, {
      props: { collapsed: false },
    });
    await wrapper.get('.flex-center').trigger('click');
    expect(wrapper.emitted('update:collapsed')?.at(-1)).toEqual([true]);

    const collapsed = mount(SidebarCollapseButton, {
      props: { collapsed: true },
    });
    await collapsed.get('.flex-center').trigger('click');
    expect(collapsed.emitted('update:collapsed')?.at(-1)).toEqual([false]);
  });
});

describe('sidebarFixedButton', () => {
  it('toggles the expandOnHover model on click', async () => {
    const wrapper = mount(SidebarFixedButton, {
      props: { expandOnHover: false },
    });
    await wrapper.get('.flex-center').trigger('click');
    expect(wrapper.emitted('update:expandOnHover')?.at(-1)).toEqual([true]);

    const on = mount(SidebarFixedButton, {
      props: { expandOnHover: true },
    });
    await on.get('.flex-center').trigger('click');
    expect(on.emitted('update:expandOnHover')?.at(-1)).toEqual([false]);
  });
});
