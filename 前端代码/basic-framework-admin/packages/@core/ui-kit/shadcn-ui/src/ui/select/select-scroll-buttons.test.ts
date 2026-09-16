import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import SelectScrollDownButton from './SelectScrollDownButton.vue';
import SelectScrollUpButton from './SelectScrollUpButton.vue';

describe('select scroll buttons', () => {
  it('renders down button with forwarded props and default slot icon', () => {
    const wrapper = mount(SelectScrollDownButton, {
      props: { class: 'custom-down', 'aria-label': 'down' },
      global: {
        stubs: {
          SelectScrollDownButton: {
            template: '<button v-bind="$attrs"><slot /></button>',
          },
        },
      },
    });

    expect(wrapper.get('button').classes()).toContain('custom-down');
    expect(wrapper.get('button').attributes('aria-label')).toBe('down');
    expect(wrapper.find('svg').exists()).toBe(true);
  });

  it('renders down button custom slot content instead of the default icon', () => {
    const wrapper = mount(SelectScrollDownButton, {
      slots: { default: '加载更多' },
      global: {
        stubs: {
          SelectScrollDownButton: {
            template: '<button v-bind="$attrs"><slot /></button>',
          },
        },
      },
    });

    expect(wrapper.get('button').text()).toBe('加载更多');
  });

  it('renders up button with forwarded props and default slot icon', () => {
    const wrapper = mount(SelectScrollUpButton, {
      props: { class: 'custom-up' },
      global: {
        stubs: {
          SelectScrollUpButton: {
            template: '<button v-bind="$attrs"><slot /></button>',
          },
        },
      },
    });

    expect(wrapper.get('button').classes()).toContain('custom-up');
    expect(wrapper.find('svg').exists()).toBe(true);
  });
});
