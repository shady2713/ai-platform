/* eslint-disable vue/multi-word-component-names -- 路由缓存契约要求 Login 名称，轻量组件用于隔离 RouterView。 */
import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';

import { describe, expect, it } from 'vitest';

import AuthenticationFormView from './form.vue';

const RoutedLogin = defineComponent({
  name: 'Login',
  template: '<article data-testid="routed-login">Login</article>',
});

const RouterViewStub = defineComponent({
  name: 'RouterView',
  setup(_, { slots }) {
    return () =>
      slots.default?.({
        Component: RoutedLogin,
        route: { fullPath: '/login' },
      });
  },
});

function mountForm(
  options: Parameters<typeof mount<typeof AuthenticationFormView>>[1] = {},
) {
  return mount(AuthenticationFormView, {
    ...options,
    global: {
      ...options.global,
      stubs: {
        ...options.global?.stubs,
        RouterView: RouterViewStub,
      },
    },
    props: {
      dataSide: 'left',
      ...options.props,
    },
  });
}

describe('authentication form view', () => {
  it('renders the routed page with its panel side and named content', () => {
    const wrapper = mountForm({
      slots: {
        copyright: '<span data-testid="copyright">Framework</span>',
        default: '<header data-testid="heading">Welcome</header>',
      },
    });

    expect(wrapper.get('[data-testid="heading"]').text()).toBe('Welcome');
    expect(
      wrapper.get('[data-testid="routed-login"]').attributes('data-side'),
    ).toBe('left');
    expect(wrapper.get('footer [data-testid="copyright"]').text()).toBe(
      'Framework',
    );
  });

  it('does not render an empty footer without copyright content', () => {
    const wrapper = mountForm({ props: { dataSide: 'bottom' } });

    expect(
      wrapper.get('[data-testid="routed-login"]').attributes('data-side'),
    ).toBe('bottom');
    expect(wrapper.find('footer').exists()).toBe(false);
  });
});
