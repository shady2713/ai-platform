import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CropperAvatar } from './index';

const mocks = vi.hoisted(() => ({
  close: vi.fn(() => Promise.resolve()),
  open: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  const ConnectedModal = defineComponent({
    name: 'ConnectedCropperModal',
    emits: ['uploadSuccess'],
    setup(_props, { attrs }) {
      return () => h('div', attrs);
    },
  });
  return {
    useVbenModal: vi.fn(() => [
      ConnectedModal,
      { close: mocks.close, open: mocks.open },
    ]),
  };
});

vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({
    name: 'IconifyIcon',
    template: '<i />',
  }),
}));

vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: mocks.showSuccessMessage,
}));

vi.mock('element-plus', () => ({
  ElButton: defineComponent({
    name: 'ElButton',
    inheritAttrs: false,
    props: { disabled: Boolean },
    emits: ['click'],
    setup(props, { attrs, emit, slots }) {
      return () =>
        h(
          'button',
          {
            ...attrs,
            disabled: props.disabled,
            onClick: () => emit('click'),
          },
          slots.default?.(),
        );
    },
  }),
}));

beforeEach(() => {
  vi.clearAllMocks();
});

describe('cropperAvatar', () => {
  it('preserves CSS units and exposes keyboard-accessible modal controls', async () => {
    const wrapper = mount(CropperAvatar, {
      props: {
        btnText: '更换头像',
        showBtn: true,
        value: '/avatar.png',
        width: '8rem',
      },
    });
    const avatarButton = wrapper.find('button[type="button"]');

    expect(wrapper.find('.inline-block').attributes('style')).toContain(
      'width: 8rem',
    );
    expect(avatarButton.attributes('aria-label')).toBe(
      'ui.cropper.selectImage',
    );
    expect(avatarButton.attributes('style')).toContain('width: 8rem');
    expect(wrapper.find('img').attributes('src')).toBe('/avatar.png');
    expect(wrapper.text()).toContain('更换头像');

    await avatarButton.trigger('click');
    expect(mocks.open).toHaveBeenCalledOnce();
    await wrapper.vm.closeModal();
    expect(mocks.close).toHaveBeenCalledOnce();
    wrapper.vm.openModal();
    expect(mocks.open).toHaveBeenCalledTimes(2);
  });

  it('honors disabled button props for both avatar and text entry points', async () => {
    const wrapper = mount(CropperAvatar, {
      props: { btnProps: { disabled: true }, showBtn: true },
    });

    const buttons = wrapper.findAll('button');
    expect(buttons).toHaveLength(2);
    expect(
      buttons.every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true);
    await buttons[0]?.trigger('click');
    await buttons[1]?.trigger('click');
    expect(mocks.open).not.toHaveBeenCalled();
  });

  it('syncs external values without echoing them and emits user upload results once', async () => {
    const wrapper = mount(CropperAvatar, {
      props: { value: '/old.png', width: 120 },
    });

    await wrapper.setProps({ value: '/external.png' });
    expect(wrapper.find('img').attributes('src')).toBe('/external.png');
    expect(wrapper.emitted('update:value')).toBeUndefined();

    wrapper
      .findComponent({ name: 'ConnectedCropperModal' })
      .vm.$emit('uploadSuccess', {
        data: { id: 7 },
        source: 'data:image/png;base64,YQ==',
      });
    await nextTick();

    expect(wrapper.find('img').attributes('src')).toBe(
      'data:image/png;base64,YQ==',
    );
    expect(wrapper.emitted('update:value')).toEqual([
      ['data:image/png;base64,YQ=='],
    ]);
    expect(wrapper.emitted('change')).toEqual([
      [
        {
          data: { id: 7 },
          source: 'data:image/png;base64,YQ==',
        },
      ],
    ]);
    expect(mocks.showSuccessMessage).toHaveBeenCalledWith(
      'ui.cropper.uploadSuccess',
    );
  });
});
