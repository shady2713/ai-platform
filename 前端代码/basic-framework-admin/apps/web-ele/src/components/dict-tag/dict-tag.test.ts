import { shallowMount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import DictTag from './dict-tag.vue';

const { getDictObj } = vi.hoisted(() => ({
  getDictObj: vi.fn(),
}));

vi.mock('@vben/hooks', () => ({ getDictObj }));

vi.mock('element-plus', () => ({
  ElTag: { name: 'ElTag', props: ['type'], template: '<span><slot /></span>' },
}));

describe('dict tag', () => {
  beforeEach(() => {
    getDictObj.mockReset();
  });

  it('renders a known dictionary label and color', () => {
    getDictObj.mockReturnValue({ colorType: 'success', label: '启用' });

    const wrapper = shallowMount(DictTag, {
      global: { renderStubDefaultSlot: true },
      props: { type: 'common_status', value: 1 },
    });

    expect(getDictObj).toHaveBeenCalledWith('common_status', '1');
    expect(wrapper.text()).toBe('启用');
    expect(wrapper.findComponent({ name: 'ElTag' }).props('type')).toBe(
      'success',
    );
  });

  it('falls back to the safe color for an unknown dictionary color', () => {
    getDictObj.mockReturnValue({ colorType: 'future-color', label: '未知' });

    const wrapper = shallowMount(DictTag, {
      props: { type: 'common_status', value: false },
    });

    expect(wrapper.findComponent({ name: 'ElTag' }).props('type')).toBe(
      'primary',
    );
  });

  it('renders nothing when the dictionary entry does not exist', () => {
    getDictObj.mockReturnValue(undefined);

    const wrapper = shallowMount(DictTag, {
      props: { type: 'common_status', value: 99 },
    });

    expect(wrapper.findComponent({ name: 'ElTag' }).exists()).toBe(false);
  });
});
