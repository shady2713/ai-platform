import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import Breadcrumb from './breadcrumb.vue';

interface MatchLike {
  meta: {
    hideChildrenInMenu?: boolean;
    hideInBreadcrumb?: boolean;
    icon?: string;
    title?: string;
  };
  name?: string;
  path: string;
}

const state = vi.hoisted(() => ({
  push: vi.fn(),
  resolve: vi.fn(),
  route: {
    matched: [] as MatchLike[],
    params: {} as Record<string, string>,
  },
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => `translated:${key}`,
}));

vi.mock('vue-router', () => ({
  useRoute: () => state.route,
  useRouter: () => ({ push: state.push, resolve: state.resolve }),
}));

vi.mock('@vben-core/shadcn-ui', async () => {
  const { defineComponent } = await import('vue');
  return {
    VbenBreadcrumbView: defineComponent({
      name: 'VbenBreadcrumbViewStub',
      props: {
        breadcrumbs: { type: Array, required: true },
        showIcon: Boolean,
        styleType: { type: String, default: undefined },
      },
      emits: ['select'],
      template: '<nav />',
    }),
  };
});

function breadcrumbView(wrapper: ReturnType<typeof mount>) {
  return wrapper.findComponent({ name: 'VbenBreadcrumbViewStub' });
}

describe('layout breadcrumb', () => {
  beforeEach(() => {
    state.push.mockReset();
    state.resolve.mockReset();
    state.route.matched = [];
    state.route.params = {};
  });

  it('builds translated visible entries and resolves dynamic routes', () => {
    state.route.params = { id: '42' };
    state.route.matched = [
      { meta: { icon: 'home-icon', title: 'routes.root' }, path: '/root' },
      {
        meta: { hideInBreadcrumb: true, title: 'routes.hidden' },
        path: '/hidden',
      },
      {
        meta: { hideChildrenInMenu: true, title: 'routes.group' },
        path: '/group',
      },
      { meta: {}, path: '/missing-title' },
      {
        meta: { title: 'routes.detail' },
        name: 'UserDetail',
        path: '/users/:id',
      },
    ];
    state.resolve.mockReturnValue({ path: '/users/42' });

    const wrapper = mount(Breadcrumb, {
      props: { showHome: true, showIcon: true, type: 'background' },
    });

    expect(breadcrumbView(wrapper).props()).toMatchObject({
      breadcrumbs: [
        {
          icon: 'mdi:home-outline',
          isHome: true,
          path: '/',
        },
        {
          icon: 'home-icon',
          path: '/root',
          title: 'translated:routes.root',
        },
        {
          path: '/users/42',
          title: 'translated:routes.detail',
        },
      ],
      showIcon: true,
      styleType: 'background',
    });
    expect(state.resolve).toHaveBeenCalledWith({
      name: 'UserDetail',
      params: { id: '42' },
    });
  });

  it('keeps an unresolved dynamic entry non-navigable', () => {
    state.route.matched = [
      { meta: { title: 'routes.unnamed' }, path: '/users/:id' },
      {
        meta: { title: 'routes.named' },
        name: 'BrokenRoute',
        path: '/broken/:id',
      },
    ];
    state.resolve.mockImplementation(() => {
      throw new Error('missing parameter');
    });

    const wrapper = mount(Breadcrumb);

    expect(breadcrumbView(wrapper).props('breadcrumbs')).toEqual([
      { path: undefined, title: 'translated:routes.unnamed' },
      { path: undefined, title: 'translated:routes.named' },
    ]);
  });

  it('hides a single entry when configured', () => {
    state.route.matched = [{ meta: { title: 'routes.only' }, path: '/only' }];

    const wrapper = mount(Breadcrumb, {
      props: { hideWhenOnlyOne: true },
    });

    expect(breadcrumbView(wrapper).props('breadcrumbs')).toEqual([]);
  });

  it('pushes only validated internal destinations', () => {
    const wrapper = mount(Breadcrumb);
    const view = breadcrumbView(wrapper);

    view.vm.$emit('select', '/system/users?enabled=true');
    view.vm.$emit('select', 'https://attacker.example/path');
    view.vm.$emit('select', '//attacker.example/path');
    view.vm.$emit('select', String.raw`/system\users`);

    expect(state.push).toHaveBeenCalledOnce();
    expect(state.push).toHaveBeenCalledWith('/system/users?enabled=true');
  });
});
