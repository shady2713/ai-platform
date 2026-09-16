import type { AxiosResponse, HttpResponse } from '@vben/request';
import type {
  AuthPermissionInfo,
  CaptchaChallenge,
  CaptchaCheckRequest,
  CaptchaGetRequest,
  CaptchaProtocolResponse,
} from '@vben/types';

import { baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  /** 登录接口参数 */
  export interface LoginParams {
    password?: string;
    username?: string;
    captchaVerification?: string;
  }

  /** 登录接口返回值 */
  export interface LoginResult {
    accessToken?: string;
    userId: number;
    expiresTime?: number;
  }

  /** 手机验证码获取接口参数 */
  export interface SmsCodeParams {
    mobile: string;
    scene: number;
  }

  /** 重置密码接口参数 */
  export interface ResetPasswordParams {
    password: string;
    mobile: string;
    code: string;
  }

  /** 认证前修改过期密码接口参数 */
  export interface ChangeExpiredPasswordParams {
    username: string;
    oldPassword: string;
    newPassword: string;
  }
}

/** 登录被强制改密门禁拦截时返回的业务错误码（后端 AUTH_PASSWORD_EXPIRED） */
export const AUTH_PASSWORD_EXPIRED_CODE = 1_002_000_019;

/** 登录（错误反馈由登录页独占处理：强制改密弹窗或内联提示，不再弹全局 toast） */
export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<AuthApi.LoginResult>('/system/auth/login', data, {
    errorMode: 'silent',
  });
}

/** 认证前修改过期密码（must_change_password 账号） */
export async function changeExpiredPasswordApi(
  data: AuthApi.ChangeExpiredPasswordParams,
) {
  return requestClient.post('/system/auth/change-expired-password', data);
}

/**
 * 刷新 accessToken。
 * 服务端按 RFC 6819 单次轮换 refresh token 并检测重放：多标签页并发刷新时后到的
 * 请求按重放处理并吊销整个刷新家族，该标签页需重新登录（已知权衡，见
 * docs/security/threat-model.md 的"已知设计权衡"）。
 */
export async function refreshTokenApi() {
  return baseRequestClient.post<
    AxiosResponse<HttpResponse<AuthApi.LoginResult>>
  >('/system/auth/refresh-token', {});
}

/** 退出登录 */
export async function logoutApi(accessToken: null | string) {
  return baseRequestClient.post(
    '/system/auth/logout',
    {},
    accessToken
      ? {
          headers: {
            Authorization: `Bearer ${accessToken}`,
          },
        }
      : undefined,
  );
}

/** 获取权限信息 */
export async function getAuthPermissionInfoApi() {
  return requestClient.get<AuthPermissionInfo>(
    '/system/auth/get-permission-info',
  );
}

/** 获取验证码 */
export async function getCaptcha(data: CaptchaGetRequest) {
  return baseRequestClient.post<
    AxiosResponse<CaptchaProtocolResponse<CaptchaChallenge>>
  >('/system/captcha/get', data);
}

/** 校验验证码 */
export async function checkCaptcha(data: CaptchaCheckRequest) {
  return baseRequestClient.post<AxiosResponse<CaptchaProtocolResponse>>(
    '/system/captcha/check',
    data,
  );
}

/** 获取登录验证码 */
export async function sendSmsCode(data: AuthApi.SmsCodeParams) {
  return requestClient.post('/system/auth/send-sms-code', data);
}

/** 通过短信重置密码 */
export async function smsResetPassword(data: AuthApi.ResetPasswordParams) {
  return requestClient.post('/system/auth/reset-password', data);
}
