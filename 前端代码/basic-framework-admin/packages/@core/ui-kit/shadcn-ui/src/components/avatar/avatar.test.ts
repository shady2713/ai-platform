import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import Avatar from './avatar.vue';

vi.mock('../../ui', () => ({
  Avatar: {
    name: 'AvatarStub',
    template: '<span class="avatar-stub"><slot /></span>',
  },
  AvatarFallback: {
    name: 'AvatarFallbackStub',
    template: '<span class="avatar-fallback-stub"><slot /></span>',
  },
  AvatarImage: {
    name: 'AvatarImageStub',
    props: ['src', 'alt'],
    template: '<img class="avatar-image-stub" :src="src" :alt="alt" />',
  },
}));

type AvatarTestProps = Pick<
  InstanceType<typeof Avatar>['$props'],
  'alt' | 'class' | 'crossOrigin' | 'dot' | 'dotClass' | 'fit' | 'size' | 'src'
>;

function mountAvatar(props: Omit<AvatarTestProps, 'src'> & { src?: string }) {
  return mount(Avatar, { props: { src: '', ...props } });
}

function findDot(wrapper: ReturnType<typeof mountAvatar>) {
  return wrapper
    .findAll('span')
    .find((item) => item.classes().includes('rounded-full'));
}

describe('vbenAvatar', () => {
  it('renders defaults without sizing styles or a status dot', () => {
    const wrapper = mountAvatar({ src: '/default.png' });

    const root = wrapper.get('div');
    const image = wrapper.get('.avatar-image-stub');

    expect(root.attributes('style')).toBeUndefined();
    expect(image.attributes('src')).toBe('/default.png');
    expect(image.attributes('alt')).toBe('avatar');
    expect(image.attributes('style')).toContain('object-fit: cover');
    expect(wrapper.get('.avatar-fallback-stub').text()).toBe('AR');
    expect(findDot(wrapper)).toBeUndefined();
  });

  it('keeps image styles empty when fit is falsy at runtime', () => {
    // 类型上 fit 枚举不可传空串，但运行时空字符串会走 imageStyle 的空分支
    const wrapper = mountAvatar({ src: '/empty-fit.png', fit: '' as never });

    expect(
      wrapper.get('.avatar-image-stub').attributes('style'),
    ).toBeUndefined();
  });

  it('skips sizing styles when size is zero', () => {
    const wrapper = mountAvatar({ size: 0, src: '/zero-size.png' });

    expect(wrapper.get('div').attributes('style')).toBeUndefined();
  });

  it('renders the status dot with the configured class', () => {
    const wrapper = mountAvatar({
      dot: true,
      dotClass: 'bg-red-500',
      src: '/dot.png',
    });
    const dot = findDot(wrapper);

    expect(dot).toBeDefined();
    expect(dot?.classes()).toContain('bg-red-500');
  });

  it('applies the class prop to the root and inner avatar', () => {
    const wrapper = mountAvatar({
      class: 'custom-avatar',
      src: '/classed.png',
    });

    expect(wrapper.get('div').attributes('class')).toContain('custom-avatar');
    expect(wrapper.get('.avatar-stub').attributes('class')).toContain(
      'custom-avatar',
    );
  });
});
