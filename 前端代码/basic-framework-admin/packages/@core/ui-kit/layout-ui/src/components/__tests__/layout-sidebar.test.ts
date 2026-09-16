import type { ComponentMountingOptions } from '@vue/test-utils';

import { mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';

import { describe, expect, it } from 'vitest';

import LayoutSidebar from '../layout-sidebar.vue';

function mountSidebar(
  props: Record<string, unknown> = {},
  options: ComponentMountingOptions<typeof LayoutSidebar> = {},
) {
  return mount(LayoutSidebar, {
    ...options,
    global: {
      ...options.global,
      stubs: {
        ...options.global?.stubs,
        VbenScrollbar: {
          template: '<div class="scrollbar-stub"><slot /></div>',
        },
      },
    },
    props: {
      extraWidth: 180,
      headerHeight: 48,
      theme: 'dark',
      width: 210,
      ...props,
    },
  });
}

/** Host binds all defineModel props, mirroring the parent usage. */
function mountSidebarWithModels(overrides: Record<string, unknown> = {}) {
  const collapse = ref(false);
  const expandOnHovering = ref(false);
  const expandOnHover = ref(false);
  const extraCollapse = ref(false);
  const extraVisible = ref(false);
  const state = {
    collapse,
    expandOnHover,
    expandOnHovering,
    extraCollapse,
    extraVisible,
  };
  const Host = defineComponent({
    setup() {
      return () =>
        h('div', [
          h('span', {
            'data-c': state.collapse.value ? '1' : '0',
            'data-h': state.expandOnHovering.value ? '1' : '0',
            'data-hover': state.expandOnHover.value ? '1' : '0',
            'data-v': state.extraVisible.value ? '1' : '0',
          }),
          h(LayoutSidebar, {
            'onUpdate:collapse': (v?: boolean) =>
              (state.collapse.value = v ?? !state.collapse.value),
            'onUpdate:expandOnHover': (v?: boolean) =>
              (state.expandOnHover.value = v ?? !state.expandOnHover.value),
            'onUpdate:expandOnHovering': (v?: boolean) =>
              (state.expandOnHovering.value =
                v ?? !state.expandOnHovering.value),
            'onUpdate:extraCollapse': (v?: boolean) =>
              (state.extraCollapse.value = v ?? !state.extraCollapse.value),
            'onUpdate:extraVisible': (v?: boolean) =>
              (state.extraVisible.value = v ?? !state.extraVisible.value),
            collapse: state.collapse.value,
            expandOnHover: state.expandOnHover.value,
            expandOnHovering: state.expandOnHovering.value,
            extraCollapse: state.extraCollapse.value,
            extraVisible: state.extraVisible.value,
            extraWidth: 180,
            headerHeight: 48,
            theme: 'dark',
            width: 210,
            ...overrides,
          }),
        ]);
    },
  });
  const wrapper = mount(Host, {
    global: { stubs: { VbenScrollbar: { template: '<div><slot /></div>' } } },
  });
  return { state, wrapper };
}

describe('layoutSidebar', () => {
  it('renders an aside with the width styles', () => {
    const wrapper = mountSidebar();
    const style = wrapper.get('aside').attributes('style');
    expect(style).toContain('width: 210px');
    expect(style).toContain('flex-basis: 210px');
    expect(style).toContain('z-index: 0');
  });

  it('hides the sidebar by pulling it off-canvas', () => {
    const wrapper = mountSidebar({ show: false });
    expect(wrapper.get('aside').attributes('style')).toContain(
      'margin-left: -210px',
    );
  });

  it('sets overflow hidden when the width collapses to zero', () => {
    const wrapper = mountSidebar({ width: 0 });
    const style = wrapper.get('aside').attributes('style');
    expect(style).toContain('width: 0px');
    expect(style).toContain('overflow: hidden');
  });

  it('renders the logo and default slots', () => {
    const wrapper = mountSidebar(
      {},
      { slots: { default: 'menu', logo: 'logo' } },
    );
    expect(wrapper.text()).toContain('logo');
    expect(wrapper.text()).toContain('menu');
  });

  it('expands on hover when the mouse enters with a real offset', async () => {
    const { wrapper } = mountSidebarWithModels();
    await wrapper.get('aside').trigger('mouseenter', { offsetX: 60 });
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-h]').attributes('data-h')).toBe('1');
    expect(wrapper.find('[data-c]').attributes('data-c')).toBe('0');
  });

  it('ignores mouse enter while expandOnHover is already active', async () => {
    const { state, wrapper } = mountSidebarWithModels({
      expandOnHover: true,
    });
    await wrapper.get('aside').trigger('mouseenter', { offsetX: 60 });
    await wrapper.vm.$nextTick();
    expect(state.expandOnHovering.value).toBe(false);
    expect(state.collapse.value).toBe(false);
  });

  it('emits leave, collapses and hides the extra panel on mouse leave', async () => {
    const { state, wrapper } = mountSidebarWithModels();
    await wrapper.get('aside').trigger('mouseleave');
    await wrapper.vm.$nextTick();
    expect(state.collapse.value).toBe(true);
    expect(state.expandOnHovering.value).toBe(false);
    expect(state.extraVisible.value).toBe(false);
  });

  it('keeps the expanded state when mouse leave happens in hover mode', async () => {
    const { state, wrapper } = mountSidebarWithModels({
      expandOnHover: true,
    });
    await wrapper.get('aside').trigger('mouseleave');
    await wrapper.vm.$nextTick();
    expect(state.collapse.value).toBe(false);
    expect(state.extraVisible.value).toBe(false);
  });

  it('skips the collapse button when requested', () => {
    const wrapper = mountSidebar({ showCollapseButton: false });
    // only the fixed button keeps the flex-center class
    expect(wrapper.findAll('.flex-center')).toHaveLength(1);
  });
});
