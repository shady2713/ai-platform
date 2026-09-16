import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import Copyright from '../copyright.vue';

describe('copyright', () => {
  it('renders the company name as plain text when no companySiteLink is provided', () => {
    const wrapper = mount(Copyright, {
      props: {
        companyName: '基础框架',
        companySiteLink: '',
      },
    });

    expect(wrapper.text()).toContain('基础框架');
    expect(wrapper.find('a').exists()).toBe(false);
  });

  it('renders the company name as a link when companySiteLink is provided', () => {
    const wrapper = mount(Copyright, {
      props: {
        companyName: '基础框架',
        companySiteLink: 'https://example.com',
      },
    });

    const link = wrapper.find('a');

    expect(link.exists()).toBe(true);
    expect(link.text()).toBe('基础框架');
    expect(link.attributes('href')).toBe('https://example.com');
  });
});
