import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import NotFound from './not-found.vue';

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

describe('fallback not-found page', () => {
  it('renders the fallback with the 404 status code', () => {
    const wrapper = mount(NotFound);
    expect(
      wrapper.find('[data-test="fallback"]').attributes('data-status'),
    ).toBe('404');
    expect((wrapper.vm.$options.name ?? '').toString()).toBe('Fallback404');
  });
});
