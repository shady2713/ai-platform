import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import DialogContent from './DialogContent.vue';

function mountDialog(props: Record<string, unknown> = {}, slots = {}) {
  return mount(DialogContent, {
    props: { open: true, ...props },
    slots: { default: '<div class="dialog-body">payload</div>', ...slots },
    global: {
      stubs: {
        DialogOverlay: {
          template: '<div class="overlay-stub"><slot /></div>',
        },
        DialogClose: {
          template: '<button class="close-stub"><slot /></button>',
        },
        DialogContent: {
          template:
            '<div class="content-stub" data-state="open"><slot /></div>',
        },
        teleport: true,
        transition: false,
      },
    },
  });
}

describe('dialogContent', () => {
  it('renders the body and close button in modal mode', () => {
    const wrapper = mountDialog({ modal: true, overlayBlur: 4, zIndex: 50 });

    expect(wrapper.find('.overlay-stub').exists()).toBe(true);
    expect(wrapper.find('.content-stub').exists()).toBe(true);
    expect(wrapper.text()).toContain('payload');
    expect(wrapper.find('.content-stub').attributes('style')).toContain(
      'z-index: 50',
    );
    expect(wrapper.find('.overlay-stub').attributes('style')).toContain(
      'blur(4px)',
    );
  });

  it('hides the overlay when modal is disabled', () => {
    const wrapper = mountDialog({ modal: false });

    expect(wrapper.find('.overlay-stub').exists()).toBe(false);
    expect(wrapper.find('.content-stub').exists()).toBe(true);
  });

  it('hides the close button when showClose is disabled', () => {
    const wrapper = mountDialog({ showClose: false });

    expect(wrapper.find('.close-stub').exists()).toBe(false);
  });

  it('emits close when the close button or overlay is clicked', async () => {
    const wrapper = mountDialog({ modal: true });

    await wrapper.find('.close-stub').trigger('click');
    expect(wrapper.emitted('close')).toHaveLength(1);

    await wrapper.find('.overlay-stub').trigger('click');
    expect(wrapper.emitted('close')).toHaveLength(2);
  });

  it('uses absolute positioning for a non-body append target', () => {
    const wrapper = mountDialog({ appendTo: '#dialog-root', open: true });

    expect(wrapper.find('.content-stub').attributes('style')).toContain(
      'position: absolute',
    );
  });

  it('applies the scale animation class variant', () => {
    const wrapper = mountDialog({ animationType: 'scale' });

    expect(wrapper.find('.content-stub').classes()).not.toContain(
      'slide-in-from-top-[48%]',
    );
  });

  it('omits z-index and blur styles when not configured', () => {
    const wrapper = mountDialog({ modal: true });

    expect(wrapper.find('.content-stub').attributes('style')).not.toContain(
      'z-index',
    );
    expect(wrapper.find('.overlay-stub').attributes('style')).not.toContain(
      'blur(',
    );
  });

  it('treats an empty or document-body append target as body', () => {
    const bodyWrapper = mountDialog({ appendTo: document.body, open: true });
    expect(bodyWrapper.find('.content-stub').attributes('style')).toContain(
      'position: fixed',
    );

    const emptyWrapper = mountDialog({ appendTo: '', open: true });
    expect(emptyWrapper.find('.content-stub').attributes('style')).toContain(
      'position: fixed',
    );
  });

  it('emits opened and closed when the content animation ends', async () => {
    const openWrapper = mountDialog({ open: true });
    await openWrapper.find('.content-stub').trigger('animationend');
    expect(openWrapper.emitted('opened')).toHaveLength(1);

    const closedWrapper = mountDialog({ open: false });
    await closedWrapper.find('.content-stub').trigger('animationend');
    expect(closedWrapper.emitted('closed')).toHaveLength(1);
  });
});
