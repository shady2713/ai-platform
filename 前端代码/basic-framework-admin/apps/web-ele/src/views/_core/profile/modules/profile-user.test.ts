import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { updateUserProfile } from '#/api/system/user/profile';
import { resolveUploadUrl } from '#/components/upload/use-upload-core';

import ProfileUser from './profile-user.vue';

const state = vi.hoisted(() => ({
  httpRequest: vi.fn(),
}));

vi.mock('@vben/icons', () => ({
  IconifyIcon: { name: 'IconifyIcon' },
}));

vi.mock('@vben/preferences', () => ({
  preferences: {
    app: { defaultAvatar: '/default-avatar.png' },
  },
}));

vi.mock('@vben/utils', () => ({
  formatDateTime: (value: string) => `fmt:${value}`,
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
    ElDescriptions: slotOnly('ElDescriptions'),
    ElDescriptionsItem: slotOnly('ElDescriptionsItem'),
    ElTooltip: slotOnly('ElTooltip'),
  };
});

vi.mock('#/api/system/user/profile', () => ({
  updateUserProfile: vi.fn(),
}));

vi.mock('#/components/cropper', async () => {
  const { defineComponent } = await import('vue');
  return {
    CropperAvatar: defineComponent({
      name: 'CropperAvatar',
      props: {
        showBtn: { type: Boolean, default: false },
        uploadApi: { type: Function, default: undefined },
        value: { type: String, default: '' },
        width: { type: Number, default: 0 },
      },
      emits: ['change'],
      template: '<button data-test="avatar" @click="$emit(\'change\')" />',
    }),
  };
});

vi.mock('#/components/upload/use-upload', () => ({
  useUpload: vi.fn(() => ({ httpRequest: state.httpRequest })),
}));

vi.mock('#/components/upload/use-upload-core', () => ({
  resolveUploadUrl: vi.fn(),
}));

function profile(): SystemUserProfileApi.UserProfileRespVO {
  return {
    createTime: '2026-09-01 09:00:00',
    dept: { id: 2, name: '研发部' },
    id: 1,
    loginDate: '2026-09-02 10:00:00',
    loginIp: '127.0.0.1',
    mobile: '13800138000',
    nickname: '管理员',
    posts: [{ id: 3, name: '后端开发' }],
    roles: [{ id: 4, name: '超级管理员' }],
    sex: 1,
    username: 'admin',
  };
}

function avatarWrapper() {
  return mount(ProfileUser, { props: { profile: profile() } });
}

describe('profile user card', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(resolveUploadUrl).mockReturnValue('http://cdn.example/a.png');
    state.httpRequest.mockResolvedValue('upload-ref');
  });

  it('renders the joined roles, posts, department and formatted dates', () => {
    const wrapper = avatarWrapper();
    const text = wrapper.text();

    expect(text).toContain('admin');
    expect(text).toContain('超级管理员');
    expect(text).toContain('13800138000');
    expect(text).toContain('研发部');
    expect(text).toContain('后端开发');
    expect(text).toContain('fmt:2026-09-01 09:00:00');
    expect(text).toContain('fmt:2026-09-02 10:00:00');
    expect(
      wrapper.findComponent({ name: 'CropperAvatar' }).props('value'),
    ).toBe('/default-avatar.png');
  });

  it('uses the profile avatar when provided and emits success on change', async () => {
    const data = profile();
    data.avatar = 'http://cdn.example/me.png';
    const wrapper = mount(ProfileUser, { props: { profile: data } });

    expect(
      wrapper.findComponent({ name: 'CropperAvatar' }).props('value'),
    ).toBe('http://cdn.example/me.png');

    await wrapper.find('[data-test="avatar"]').trigger('click');
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('uploads a new avatar and persists the resolved URL', async () => {
    const wrapper = avatarWrapper();
    const blob = new Blob(['avatar-bytes'], { type: 'image/png' });

    await (
      wrapper
        .findComponent({ name: 'CropperAvatar' })
        .props('uploadApi') as (args: {
        file: Blob;
        filename: string;
      }) => Promise<void>
    )({ file: blob, filename: 'me.png' });

    expect(state.httpRequest).toHaveBeenCalledOnce();
    const uploaded = state.httpRequest.mock.calls[0]?.[0] as File;
    expect(uploaded.name).toBe('me.png');
    expect(uploaded.type).toBe('image/png');
    expect(resolveUploadUrl).toHaveBeenCalledWith('upload-ref');
    expect(updateUserProfile).toHaveBeenCalledWith({
      avatar: 'http://cdn.example/a.png',
    });
  });

  it('fails closed when the upload response carries no URL', async () => {
    vi.mocked(resolveUploadUrl).mockReturnValue('');
    const wrapper = avatarWrapper();

    await expect(
      (
        wrapper
          .findComponent({ name: 'CropperAvatar' })
          .props('uploadApi') as (args: {
          file: Blob;
          filename: string;
        }) => Promise<void>
      )({ file: new Blob(['x']), filename: 'x.png' }),
    ).rejects.toThrow('头像上传接口未返回 URL');

    expect(updateUserProfile).not.toHaveBeenCalled();
  });
});
