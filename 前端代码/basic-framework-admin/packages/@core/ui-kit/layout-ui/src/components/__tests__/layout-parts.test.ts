import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import LayoutContent from '../layout-content.vue';
import LayoutFooter from '../layout-footer.vue';
import LayoutHeader from '../layout-header.vue';
import LayoutTabbar from '../layout-tabbar.vue';

describe('layoutContent', () => {
  const baseProps = {
    contentCompact: 'wide' as const,
    contentCompactWidth: 1200,
    padding: 1,
    paddingBottom: 2,
    paddingLeft: 3,
    paddingRight: 4,
    paddingTop: 5,
  };

  it('renders the default slot inside a main element', () => {
    const wrapper = mount(LayoutContent, {
      props: baseProps,
      slots: { default: 'content-area' },
      global: { stubs: { Slot: true } },
    });
    const main = wrapper.get('main');
    expect(main.text()).toBe('content-area');
    expect(main.attributes('style')).toContain('flex-grow: 1');
    expect(main.attributes('style')).toContain('padding: 5px 4px 2px 3px');
  });

  it('applies the compact width when contentCompact is compact', () => {
    const wrapper = mount(LayoutContent, {
      props: { ...baseProps, contentCompact: 'compact' },
      global: { stubs: { Slot: true } },
    });
    const style = wrapper.get('main').attributes('style');
    expect(style).toContain('width: 1200px');
    expect(style).toContain('margin: 0px auto');
  });

  it('writes no compact style for wide content', () => {
    const wrapper = mount(LayoutContent, {
      props: baseProps,
      global: { stubs: { Slot: true } },
    });
    const style = wrapper.get('main').attributes('style');
    expect(style).not.toContain('width:');
    expect(style).not.toContain('margin:');
  });
});

describe('layoutFooter', () => {
  it('renders static positioned footer with the given height', () => {
    const wrapper = mount(LayoutFooter, {
      props: {
        fixed: false,
        height: 32,
        show: true,
        width: '100%',
        zIndex: 10,
      },
      slots: { default: 'footer' },
    });
    const footer = wrapper.get('footer');
    expect(footer.text()).toBe('footer');
    expect(footer.attributes('style')).toContain('height: 32px');
    expect(footer.attributes('style')).toContain('position: static');
    expect(footer.attributes('style')).toContain('margin-bottom: 0px');
  });

  it('hides the footer by pulling it up when show is false', () => {
    const wrapper = mount(LayoutFooter, {
      props: { fixed: true, height: 32, show: false, width: '50%', zIndex: 5 },
    });
    const style = wrapper.get('footer').attributes('style');
    expect(style).toContain('position: fixed');
    expect(style).toContain('margin-bottom: -32px');
    expect(style).toContain('width: 50%');
  });
});

describe('layoutHeader', () => {
  const baseProps = {
    fullWidth: true,
    height: 48,
    isMobile: false,
    show: true,
    sidebarWidth: 180,
    theme: undefined as string | undefined,
    width: '100%',
    zIndex: 100,
  };

  it('renders the default slot and theme class', () => {
    const wrapper = mount(LayoutHeader, {
      props: { ...baseProps, theme: 'dark' },
      slots: { default: 'header' },
    });
    const header = wrapper.get('header');
    expect(header.classes()).toContain('dark');
    expect(header.text()).toBe('header');
    expect(header.attributes('style')).toContain('height: 48px');
    expect(header.attributes('style')).toContain('margin-top: 0px');
  });

  it('reserves the right edge only when fullWidth and show are true', () => {
    const wrapper = mount(LayoutHeader, { props: baseProps });
    expect(wrapper.get('header').attributes('style')).toContain('right: 0px');

    const hidden = mount(LayoutHeader, {
      props: { ...baseProps, show: false },
    });
    expect(hidden.get('header').attributes('style')).not.toContain('right:');

    const leftAligned = mount(LayoutHeader, {
      props: { ...baseProps, fullWidth: false },
    });
    expect(leftAligned.get('header').attributes('style')).not.toContain(
      'right:',
    );
  });

  it('pulls the header up when hidden', () => {
    const wrapper = mount(LayoutHeader, {
      props: { ...baseProps, show: false },
    });
    expect(wrapper.get('header').attributes('style')).toContain(
      'margin-top: -48px',
    );
  });

  it('sizes the logo slot with the sidebar width or mobile size', () => {
    const desktop = mount(LayoutHeader, {
      props: baseProps,
      slots: { logo: 'logo' },
    });
    expect(desktop.find('div').attributes('style')).toContain(
      'min-width: 180px',
    );

    const mobile = mount(LayoutHeader, {
      props: { ...baseProps, isMobile: true },
      slots: { logo: 'logo' },
    });
    expect(mobile.find('div').attributes('style')).toContain('min-width: 40px');
  });

  it('skips the logo block when no logo slot is provided', () => {
    const wrapper = mount(LayoutHeader, { props: baseProps });
    expect(wrapper.find('div').exists()).toBe(false);
  });
});

describe('layoutTabbar', () => {
  it('applies the height and renders slot content', () => {
    const wrapper = mount(LayoutTabbar, {
      props: { height: 40 },
      slots: { default: 'tabs' },
    });
    const section = wrapper.get('section');
    expect(section.text()).toBe('tabs');
    expect(section.attributes('style')).toContain('height: 40px');
  });
});
