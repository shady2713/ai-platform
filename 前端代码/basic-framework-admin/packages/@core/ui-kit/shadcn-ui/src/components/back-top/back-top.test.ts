import { flushPromises, mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import BackTop from './back-top.vue';

vi.mock('../button', () => ({
  VbenButton: {
    name: 'VbenButtonStub',
    template: '<button type="button" class="back-top-button"><slot /></button>',
  },
}));

vi.mock('@vben-core/icons', () => ({
  ArrowUpToLine: {
    name: 'ArrowUpToLineStub',
    template: '<span class="arrow-up-to-line" />',
  },
}));

function appendScrollTarget(id: string) {
  const target = document.createElement('div');
  target.id = id;
  Object.defineProperty(target, 'scrollTop', {
    configurable: true,
    value: 0,
    writable: true,
  });
  document.body.append(target);
  return target;
}

afterEach(() => {
  document.body.innerHTML = '';
});

describe('vbenBackTop', () => {
  it('stays hidden on the document scroll position of zero', () => {
    const wrapper = mount(BackTop);

    expect(wrapper.find('.back-top-button').exists()).toBe(false);
  });

  it('becomes visible once the target scrolls past the threshold', async () => {
    const target = appendScrollTarget('scroll-area');
    const scrollTo = vi.fn();
    Object.defineProperty(target, 'scrollTo', { value: scrollTo });

    const wrapper = mount(BackTop, {
      props: {
        bottom: 10,
        right: 12,
        target: '#scroll-area',
        visibilityHeight: 100,
      },
    });
    // useBackTop attaches the scroll listener on a post-flush watcher
    await flushPromises();

    expect(wrapper.find('.back-top-button').exists()).toBe(false);

    Object.defineProperty(target, 'scrollTop', { value: 200 });
    target.dispatchEvent(new Event('scroll', { bubbles: true }));
    await vi.waitFor(() => {
      expect(wrapper.find('.back-top-button').exists()).toBe(true);
    });

    const button = wrapper.get('.back-top-button');
    expect(button.attributes('style')).toContain('bottom: 10px');
    expect(button.attributes('style')).toContain('right: 12px');

    await button.trigger('click');
    expect(scrollTo).toHaveBeenCalledWith({ behavior: 'smooth', top: 0 });
  });

  it('throws when the configured target does not exist', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      try {
        mount(BackTop, { props: { target: '#missing-target' } });
      } catch (error) {
        expect(String(error)).toContain(
          'target does not exist: #missing-target',
        );
        return;
      }
      expect(errorSpy).toHaveBeenCalledWith(
        expect.stringContaining('target does not exist: #missing-target'),
      );
    } finally {
      errorSpy.mockRestore();
    }
  });
});
