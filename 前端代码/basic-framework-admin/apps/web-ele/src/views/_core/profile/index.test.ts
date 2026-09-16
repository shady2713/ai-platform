import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getAuthPermissionInfoApi } from '#/api';
import { getUserProfile } from '#/api/system/user/profile';

import Profile from './index.vue';

const state = vi.hoisted(() => ({
  profileProp: undefined as unknown,
  userStore: { setUserInfo: vi.fn() },
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', slots.default?.());
      },
    }),
  };
});

vi.mock('@vben/stores', () => ({
  useUserStore: () => state.userStore,
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const slotOnly = (name: string) =>
    defineComponent({
      name,
      setup(_props, { slots }) {
        return () => h('section', slots.default?.());
      },
    });
  return {
    ElCard: slotOnly('ElCard'),
    ElTabPane: slotOnly('ElTabPane'),
    ElTabs: slotOnly('ElTabs'),
  };
});

vi.mock('#/api', () => ({
  getAuthPermissionInfoApi: vi.fn(),
}));

vi.mock('#/api/system/user/profile', () => ({
  getUserProfile: vi.fn(),
}));

vi.mock('./modules/base-info.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'BaseInfo', template: '<div />' }),
  };
});

vi.mock('./modules/profile-user.vue', async () => {
  const { defineComponent, h, watch } = await import('vue');
  return {
    default: defineComponent({
      name: 'ProfileUserStub',
      props: { profile: { type: Object, default: undefined } },
      setup(props, { emit }) {
        watch(
          () => props.profile,
          (value) => {
            state.profileProp = value;
          },
          { immediate: true },
        );
        return () =>
          h(
            'button',
            { 'data-test': 'profile-success', onClick: () => emit('success') },
            '个人资料',
          );
      },
    }),
  };
});

vi.mock('./modules/reset-pwd.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'ResetPwdStub', template: '<div />' }),
  };
});

function profile(): SystemUserProfileApi.UserProfileRespVO {
  return {
    createTime: '2026-09-01 09:00:00',
    dept: null,
    id: 1,
    loginDate: '2026-09-01 09:00:00',
    loginIp: '127.0.0.1',
    nickname: '管理员',
    posts: [],
    roles: [],
    sex: 1,
    username: 'admin',
  };
}

describe('profile page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.profileProp = undefined;
    vi.mocked(getUserProfile).mockResolvedValue(profile());
    vi.mocked(getAuthPermissionInfoApi).mockResolvedValue({
      menus: [],
      permissions: [],
      roles: [],
      user: {
        avatar: '',
        deptId: 1,
        id: 1,
        nickname: '管理员',
        username: 'admin',
      },
    });
  });

  it('loads the profile on mount and passes it to the user card', async () => {
    mount(Profile);

    await flushPromises();

    expect(getUserProfile).toHaveBeenCalledOnce();
    expect(state.profileProp).toEqual(profile());
  });

  it('refreshes the profile and the store after a child edit succeeds', async () => {
    const wrapper = mount(Profile);
    await flushPromises();

    await wrapper.find('[data-test="profile-success"]').trigger('click');
    await flushPromises();

    expect(getUserProfile).toHaveBeenCalledTimes(2);
    expect(getAuthPermissionInfoApi).toHaveBeenCalledOnce();
    expect(state.userStore.setUserInfo).toHaveBeenCalledWith({
      avatar: '',
      deptId: 1,
      id: 1,
      nickname: '管理员',
      username: 'admin',
    });
  });
});
