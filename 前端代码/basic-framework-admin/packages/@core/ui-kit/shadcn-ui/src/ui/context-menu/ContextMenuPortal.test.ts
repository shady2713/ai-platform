import { flushPromises, mount } from '@vue/test-utils';

import { afterEach, describe, expect, it } from 'vitest';

import ContextMenuPortal from './ContextMenuPortal.vue';

function mountPortal(props: Record<string, unknown> = {}) {
  return mount(ContextMenuPortal, {
    props,
    slots: { default: '<div class="portal-body">menu</div>' },
  });
}

afterEach(() => {
  document.body.innerHTML = '';
});

describe('contextMenuPortal', () => {
  it('teleports the slot content to document.body once mounted', async () => {
    const wrapper = mountPortal();
    await flushPromises();

    expect(wrapper.find('.portal-body').exists()).toBe(false);
    expect(document.body.querySelector('.portal-body')?.textContent).toBe(
      'menu',
    );
    wrapper.unmount();
  });

  it('forwards the to prop to a custom portal target', async () => {
    const target = document.createElement('section');
    target.id = 'menu-portal-target';
    document.body.append(target);

    const wrapper = mountPortal({ to: '#menu-portal-target' });
    await flushPromises();

    expect(target.querySelector('.portal-body')?.textContent).toBe('menu');
    wrapper.unmount();
    expect(target.querySelector('.portal-body')).toBeNull();
  });

  it('renders inline when the portal is disabled', async () => {
    const wrapper = mountPortal({ disabled: true });
    await flushPromises();

    expect(wrapper.find('.portal-body').exists()).toBe(true);
    expect(wrapper.text()).toContain('menu');
    wrapper.unmount();
  });

  it('removes the teleported content on unmount', async () => {
    const wrapper = mountPortal();
    await flushPromises();
    expect(document.body.querySelector('.portal-body')).not.toBeNull();

    wrapper.unmount();
    expect(document.body.querySelector('.portal-body')).toBeNull();
  });
});
