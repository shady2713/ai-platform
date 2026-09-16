import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { updateUserProfile } from '#/api/system/user/profile';
import { showSuccessMessage } from '#/utils/feedback';

import BaseInfo from './base-info.vue';

type FormOptions = {
  handleSubmit: (values: Record<string, unknown>) => Promise<void>;
};

const testState = vi.hoisted(() => ({
  formApi: {
    setLoading: vi.fn(),
    setValues: vi.fn(),
  },
  formOptions: null as FormOptions | null,
  logError: vi.fn(),
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('@vben/constants', () => ({
  DICT_TYPE: {
    SYSTEM_USER_SEX: 'system_user_sex',
  },
}));

vi.mock('@vben/hooks', () => ({
  getDictOptions: vi.fn(() => []),
}));

vi.mock('@vben/utils', () => ({
  logError: testState.logError,
}));

vi.mock('#/adapter/form', async () => {
  const { defineComponent } = await import('vue');
  return {
    buildOptionalEmailSchema: vi.fn(),
    buildOptionalMobileSchema: vi.fn(),
    useVbenForm: (options: FormOptions) => {
      testState.formOptions = options;
      return [defineComponent({ template: '<form />' }), testState.formApi];
    },
    z: { number: vi.fn() },
  };
});

vi.mock('#/api/system/user/profile', () => ({
  updateUserProfile: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
}));

function profile(): SystemUserProfileApi.UserProfileRespVO {
  return {
    createTime: '2026-09-01 09:00:00',
    dept: null,
    id: 1,
    loginDate: '2026-09-01 09:00:00',
    loginIp: '127.0.0.1',
    nickname: '旧昵称',
    posts: [],
    roles: [],
    sex: 1,
    username: 'admin',
  };
}

function submit() {
  if (!testState.formOptions) {
    throw new Error('表单提交回调未初始化');
  }
  return testState.formOptions.handleSubmit;
}

describe('profile base info form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    testState.formOptions = null;
  });

  it('提交合法资料后更新接口、关闭加载并通知父组件', async () => {
    const wrapper = mount(BaseInfo, { props: { profile: profile() } });
    const values = {
      email: 'user@example.com',
      mobile: '13800138000',
      nickname: '新昵称',
      sex: 2,
    };

    await submit()(values);

    expect(updateUserProfile).toHaveBeenCalledWith(values);
    expect(testState.formApi.setValues).toHaveBeenCalledWith(profile());
    expect(testState.formApi.setLoading).toHaveBeenNthCalledWith(1, true);
    expect(testState.formApi.setLoading).toHaveBeenLastCalledWith(false);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('更新失败时记录上下文且不发送成功事件', async () => {
    const failure = new Error('network unavailable');
    vi.mocked(updateUserProfile).mockRejectedValueOnce(failure);
    const wrapper = mount(BaseInfo);

    await submit()({ nickname: '新昵称', sex: 1 });

    expect(testState.logError).toHaveBeenCalledWith(
      'profile:base-info:submit',
      failure,
    );
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toBeUndefined();
    expect(testState.formApi.setLoading).toHaveBeenLastCalledWith(false);
  });
});
