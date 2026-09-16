import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import BreadcrumbBackground from './breadcrumb-background.vue';
import Breadcrumb from './breadcrumb.vue';

const items = [
  { path: '/system', title: 'System' },
  { path: '/system/users', title: 'Users' },
];

describe('breadcrumb semantics', () => {
  it('renders normal navigation as a button and the current item as text', async () => {
    const wrapper = mount(Breadcrumb, {
      props: { breadcrumbs: items },
    });

    const navigationButton = wrapper.get('button[type="button"]');
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false);
    expect(wrapper.get('[aria-current="page"]').text()).toContain('Users');

    await navigationButton.trigger('click');
    expect(wrapper.emitted('select')).toEqual([['/system']]);
  });

  it('keeps the background current item static', async () => {
    const wrapper = mount(BreadcrumbBackground, {
      props: { breadcrumbs: items },
    });

    const navigationButton = wrapper.get('button[type="button"]');
    expect(wrapper.find('a').exists()).toBe(false);
    expect(wrapper.get('[aria-current="page"]').text()).toContain('Users');

    await navigationButton.trigger('click');
    expect(wrapper.emitted('select')).toEqual([['/system']]);
  });

  it('does not emit navigation for entries without a path', async () => {
    const breadcrumbs = [
      { title: 'Group' },
      { path: '/system/users', title: 'Users' },
    ];
    const normalWrapper = mount(Breadcrumb, {
      props: { breadcrumbs },
    });
    const backgroundWrapper = mount(BreadcrumbBackground, {
      props: { breadcrumbs },
    });

    await normalWrapper.get('button[type="button"]').trigger('click');
    await backgroundWrapper.get('button[type="button"]').trigger('click');

    expect(normalWrapper.emitted('select')).toBeUndefined();
    expect(backgroundWrapper.emitted('select')).toBeUndefined();
  });

  it('emits a selected dropdown destination', async () => {
    const wrapper = mount(Breadcrumb, {
      attachTo: document.body,
      props: {
        breadcrumbs: [
          {
            items: [{ path: '/system/roles', title: 'Roles' }],
            title: 'System',
          },
          { path: '/system/users', title: 'Users' },
        ],
      },
    });

    await wrapper.get('button').trigger('click');
    const menuItem =
      document.body.querySelector<HTMLElement>('[role="menuitem"]');
    expect(menuItem).not.toBeNull();
    menuItem?.click();

    expect(wrapper.emitted('select')).toEqual([['/system/roles']]);
    wrapper.unmount();
  });
});
