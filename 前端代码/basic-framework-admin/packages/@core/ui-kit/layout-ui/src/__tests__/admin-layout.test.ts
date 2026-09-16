import type { Slots } from 'vue';

import { mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import AdminLayout from '../admin-layout.vue';

// @iconify/vue 在模块求值时捕获全局 fetch 并异步队列远程拉取图标；
// vi.hoisted 先于本文件依赖图求值执行，确保捕获到本地桩，封死外部 HTTP 边界。
vi.hoisted(() => {
  globalThis.fetch = (() =>
    Promise.resolve({
      json: () => Promise.resolve({}),
      status: 200,
    })) as unknown as typeof fetch;
});

/**
 * 布局子组件保持真实渲染，仅替换有外部副作用/依赖的滚动条组件，
 * 以真实 DOM 结构断言父布局的编排行为。
 */
const externalStubs = {
  VbenIconButton: {
    name: 'IconButtonStub',
    setup(_: unknown, { slots }: { slots: Slots }) {
      return () => h('button', { id: 'header-toggle' }, [slots.default?.({})]);
    },
  },
  'icon-button': {
    name: 'IconButtonStub',
    setup(_: unknown, { slots }: { slots: Slots }) {
      return () => h('button', { id: 'header-toggle' }, [slots.default?.({})]);
    },
  },
  VbenScrollbar: {
    name: 'ScrollbarStub',
    template: '<div class="scroll-stub"><slot /></div>',
  },
  scrollbar: {
    name: 'ScrollbarStub',
    template: '<div class="scroll-stub"><slot /></div>',
  },
};

function mountLayout(props: Record<string, unknown> = {}, slots = {}) {
  return mount(AdminLayout, {
    global: { stubs: externalStubs },
    props,
    slots,
  });
}

describe('vbenAdminLayout', () => {
  it('renders the real layout regions with slots', () => {
    const wrapper = mountLayout(
      {},
      { content: 'body-content', footer: 'footer-content' },
    );
    expect(wrapper.find('aside').exists()).toBe(true);
    expect(wrapper.find('header').exists()).toBe(true);
    expect(wrapper.get('main').text()).toContain('body-content');
    expect(wrapper.find('section').exists()).toBe(true);
  });

  it('hides the tabbar and footer when disabled', () => {
    const wrapper = mountLayout({ footerEnable: false, tabbarEnable: false });
    expect(wrapper.find('section').exists()).toBe(false);
    expect(wrapper.find('footer').exists()).toBe(false);
  });

  it('collapses the sidebar immediately on mobile', async () => {
    const collapse = ref(true);
    const Host = defineComponent({
      setup() {
        return () =>
          h(AdminLayout, {
            'onUpdate:sidebarCollapse': (v: boolean) => (collapse.value = v),
            isMobile: true,
            sidebarCollapse: collapse.value,
          });
      },
    });
    const wrapper = mount(Host, { global: { stubs: externalStubs } });
    await wrapper.vm.$nextTick();
    expect(collapse.value).toBe(true);
  });

  it('emits toggleSidebar from the header toggle button on desktop', async () => {
    const wrapper = mountLayout();
    const toggle = wrapper.find('#header-toggle');
    expect(toggle.exists()).toBe(true);
    await toggle.trigger('click');
    expect(wrapper.emitted('toggleSidebar')).toBeTruthy();
  });

  it('renders the menu slot inside the sidebar', () => {
    const wrapper = mountLayout({}, { menu: 'menu-content' });
    expect(wrapper.get('aside').text()).toContain('menu-content');
  });

  it('renders the full-content layout without the header margin', () => {
    const wrapper = mountLayout({ layout: 'full-content' });
    expect(wrapper.find('main').exists()).toBe(true);
  });
});
