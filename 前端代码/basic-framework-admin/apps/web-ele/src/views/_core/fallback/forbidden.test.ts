import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import Forbidden from './forbidden.vue';

vi.mock('@vben/common-ui', async () => {
  const { defineComponent } = await import('vue');
  return {
    Fallback: defineComponent({
      name: 'FallbackStub',
      props: { status: { type: String, default: '' } },
      template: '<div data-test="fallback" :data-status="status" />',
    }),
  };
});

describe('fallback forbidden page', () => {
  it('renders the fallback with the 403 status code', () => {
    const wrapper = mount(Forbidden);
    expect(
      wrapper.find('[data-test="fallback"]').attributes('data-status'),
    ).toBe('403');
    expect((wrapper.vm.$options.name ?? '').toString()).toBe('Fallback403');
  });
});
