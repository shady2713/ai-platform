import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import { Description } from './index';
import { useDescription } from './use-description';

vi.mock('@vben/utils', () => ({
  get: vi.fn(),
  getNestedValue: vi.fn(),
  isFunction: vi.fn(),
  logWarn: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    ElDescriptions: defineComponent({
      name: 'ElDescriptions',
      setup(_props, { slots }) {
        return () => h('dl', slots.default?.());
      },
    }),
    ElDescriptionsItem: defineComponent({
      name: 'ElDescriptionsItem',
      setup(_props, { slots }) {
        return () => h('dd', slots.default?.());
      },
    }),
  };
});

describe('description index contract', () => {
  it('re-exports the Description component and the useDescription hook', () => {
    expect(Description).toBeDefined();
    expect(useDescription).toBeTypeOf('function');
  });

  it('renders a schema-free description with the component name', () => {
    const wrapper = mount(Description);
    expect(wrapper.findComponent({ name: 'SchemaDescription' }).exists()).toBe(
      true,
    );
    wrapper.unmount();
  });
});
