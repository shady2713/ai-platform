import type { VueWrapper } from '@vue/test-utils';

import type { MenuRecordRaw } from '@vben/types';

import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import SearchPanel from './search-panel.vue';

const mocks = vi.hoisted(() => ({
  openWindow: vi.fn(),
  push: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
}));

vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));

vi.mock('@vben/utils', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@vben/utils')>();
  return { ...actual, openWindow: mocks.openWindow };
});

vi.mock('@vben/icons', async () => {
  const { defineComponent } = await import('vue');
  const IconStub = defineComponent({ template: '<i />' });
  return { SearchX: IconStub, X: IconStub };
});

vi.mock('@vben-core/shadcn-ui', async () => {
  const { defineComponent } = await import('vue');
  return {
    VbenIcon: defineComponent({ template: '<i />' }),
    VbenScrollbar: defineComponent({ template: '<div><slot /></div>' }),
  };
});

const menus: MenuRecordRaw[] = [
  { name: 'User Admin', path: '/system/user' },
  { name: 'Audit Center', path: 'https://example.com/audit' },
  { disabled: true, name: 'Disabled Area', path: '/disabled' },
  { name: 'Unsafe Link', path: 'http://example.com/unsafe' },
];
const mountedPanels: VueWrapper[] = [];

function mountPanel(active = true) {
  const wrapper = mount(SearchPanel, {
    props: { active, menus },
  });
  mountedPanels.push(wrapper);
  return wrapper;
}

async function searchFor(
  wrapper: ReturnType<typeof mountPanel>,
  keyword: string,
) {
  await wrapper.setProps({ keyword });
  await vi.advanceTimersByTimeAsync(250);
  await nextTick();
}

describe('search panel', () => {
  beforeEach(() => {
    localStorage.clear();
    mocks.openWindow.mockReset();
    mocks.push.mockReset();
    vi.useFakeTimers();
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      value: vi.fn(),
    });
  });

  afterEach(() => {
    for (const wrapper of mountedPanels.splice(0)) {
      wrapper.unmount();
    }
    vi.useRealTimers();
  });

  it('performs deterministic fuzzy matching and excludes disabled menus', async () => {
    const wrapper = mountPanel();
    await searchFor(wrapper, 'uadm');

    const results = wrapper.findAll('[data-search-item]');
    expect(results).toHaveLength(1);
    expect(results[0]?.text()).toContain('User Admin');

    await searchFor(wrapper, 'disabled');
    expect(wrapper.findAll('[data-search-item]')).toHaveLength(0);
  });

  it('ignores global keyboard navigation while the panel is inactive', async () => {
    const wrapper = mountPanel(false);
    await searchFor(wrapper, 'user');

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await nextTick();
    expect(mocks.push).not.toHaveBeenCalled();

    await wrapper.setProps({ active: true });
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await nextTick();
    expect(mocks.push).toHaveBeenCalledWith({
      path: '/system/user',
      replace: true,
    });
  });

  it('selects the clicked result without relying on mouse hover state', async () => {
    const wrapper = mountPanel();
    await searchFor(wrapper, 'a');
    const results = wrapper.findAll('[data-search-item]');

    await results[1]?.get('button').trigger('click');
    await nextTick();

    expect(mocks.openWindow).toHaveBeenCalledWith('https://example.com/audit');
    expect(mocks.push).not.toHaveBeenCalled();
  });

  it('rejects unsafe destinations before history or navigation changes', async () => {
    const wrapper = mountPanel();
    await searchFor(wrapper, 'unsafe');

    await wrapper.get('[data-search-item] > button').trigger('click');

    expect(mocks.openWindow).not.toHaveBeenCalled();
    expect(mocks.push).not.toHaveBeenCalled();
    expect(wrapper.emitted('close')).toBeUndefined();
  });

  it('exposes an accessible control for removing a result', async () => {
    const wrapper = mountPanel();
    await searchFor(wrapper, 'user');

    await wrapper.get('button[aria-label="common.delete"]').trigger('click');

    expect(wrapper.findAll('[data-search-item]')).toHaveLength(0);
  });
});
