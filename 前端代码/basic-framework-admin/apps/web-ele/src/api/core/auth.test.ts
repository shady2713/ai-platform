import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  changeExpiredPasswordApi,
  checkCaptcha,
  getAuthPermissionInfoApi,
  getCaptcha,
  loginApi,
  logoutApi,
  refreshTokenApi,
  sendSmsCode,
  smsResetPassword,
} from './auth';

const requestClient = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));
const baseRequestClient = vi.hoisted(() => ({
  post: vi.fn(),
}));

vi.mock('#/api/request', () => ({ baseRequestClient, requestClient }));

describe('auth API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps login and expired password changes', async () => {
    const login = { password: 'old-password', username: 'admin' };
    const change = {
      username: 'admin',
      oldPassword: 'old-password',
      newPassword: 'violet river orbits quietly!',
    };
    await loginApi(login);
    await changeExpiredPasswordApi(change);
    expect(requestClient.post.mock.calls).toEqual([
      // 登录错误反馈由页面独占处理，故声明 silent 错误模式
      ['/system/auth/login', login, { errorMode: 'silent' }],
      ['/system/auth/change-expired-password', change],
    ]);
  });

  it('keeps refresh credentials in cookies and access tokens in headers', async () => {
    const captcha = { captchaType: 'blockPuzzle' as const };
    const captchaCheck = {
      captchaType: 'blockPuzzle' as const,
      pointJson: '{"x":1,"y":5}',
      token: 'captcha-token',
    };
    const sms = { mobile: '13800138000', scene: 1 };
    const reset = { code: '123456', mobile: sms.mobile, password: 'new-pass' };

    await refreshTokenApi();
    await logoutApi('access-token');
    await logoutApi(null);
    await getAuthPermissionInfoApi();
    await getCaptcha(captcha);
    await checkCaptcha(captchaCheck);
    await sendSmsCode(sms);
    await smsResetPassword(reset);

    expect(baseRequestClient.post.mock.calls).toEqual([
      ['/system/auth/refresh-token', {}],
      [
        '/system/auth/logout',
        {},
        { headers: { Authorization: 'Bearer access-token' } },
      ],
      ['/system/auth/logout', {}, undefined],
      ['/system/captcha/get', captcha],
      ['/system/captcha/check', captchaCheck],
    ]);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/system/auth/get-permission-info',
    );
    expect(requestClient.post).toHaveBeenNthCalledWith(
      1,
      '/system/auth/send-sms-code',
      sms,
    );
    expect(requestClient.post).toHaveBeenNthCalledWith(
      2,
      '/system/auth/reset-password',
      reset,
    );
  });
});
