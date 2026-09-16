import { beforeEach, describe, expect, it, vi } from 'vitest';

import { refreshTokenApi } from './core';
import { baseRequestClient, requestClient } from './request';

interface TestRequestConfig {
  __isRetryRequest?: boolean;
  headers: Record<string, null | string>;
  url?: string;
}

interface TestRequestInterceptor {
  fulfilled: (config: TestRequestConfig) => Promise<TestRequestConfig>;
}

interface TestResponseInterceptor {
  rejected?: (error: unknown) => Promise<unknown> | unknown;
}

interface AuthenticateOptions {
  doReAuthenticate: (error?: unknown) => Promise<void>;
  doRefreshToken: (error?: unknown) => Promise<string>;
  shouldAuthenticate: (error: unknown) => boolean;
}

type ErrorMessageCallback = (message: string, error: unknown) => void;

const mocks = vi.hoisted(() => ({
  accessStore: {
    accessToken: null as null | string,
    isAccessChecked: false,
    setAccessToken: vi.fn(),
    setLoginExpired: vi.fn(),
  },
  authenticateOptions: undefined as AuthenticateOptions | undefined,
  clientInstances: [] as Array<{
    options: Record<string, unknown>;
    request: ReturnType<typeof vi.fn>;
  }>,
  errorMessageCallback: undefined as ErrorMessageCallback | undefined,
  logWarn: vi.fn(),
  logout: vi.fn(),
  requestInterceptors: [] as TestRequestInterceptor[],
  responseInterceptors: [] as TestResponseInterceptor[],
  showRequestError: vi.fn(),
}));

vi.mock('@vben/hooks', () => ({ useAppConfig: () => ({ apiURL: '/api' }) }));
vi.mock('@vben/preferences', () => ({
  preferences: {
    app: {
      enableRefreshToken: true,
      locale: 'zh-CN',
      loginExpiredMode: 'modal',
    },
  },
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => mocks.accessStore }));
vi.mock('@vben/utils', () => ({ logWarn: mocks.logWarn }));
vi.mock('@vben/request', () => {
  class MockRequestClient {
    request = vi.fn(async () => 'retried');

    constructor(options: Record<string, unknown>) {
      mocks.clientInstances.push({ options, request: this.request });
    }

    addRequestInterceptor(config: TestRequestInterceptor) {
      mocks.requestInterceptors.push(config);
    }

    addResponseInterceptor(config: TestResponseInterceptor) {
      mocks.responseInterceptors.push(config);
    }
  }

  return {
    RequestClient: MockRequestClient,
    authenticateResponseInterceptor: vi.fn((options: AuthenticateOptions) => {
      mocks.authenticateOptions = options;
      return { kind: 'authenticate' };
    }),
    defaultResponseInterceptor: vi.fn(() => ({ kind: 'default' })),
    errorMessageResponseInterceptor: vi.fn((callback: ErrorMessageCallback) => {
      mocks.errorMessageCallback = callback;
      return { kind: 'error-message' };
    }),
  };
});
vi.mock('#/store', () => ({
  useAuthStore: () => ({ logout: mocks.logout }),
}));
vi.mock('#/utils/feedback', () => ({
  showRequestError: mocks.showRequestError,
}));
vi.mock('./core', () => ({ refreshTokenApi: vi.fn() }));

const { authenticateResponseInterceptor } =
  await vi.importActual<typeof import('@vben/request')>('@vben/request');

describe('request client assembly', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.accessStore.accessToken = null;
    mocks.accessStore.isAccessChecked = false;
    mocks.accessStore.setAccessToken.mockImplementation(
      (token: null | string) => {
        mocks.accessStore.accessToken = token;
      },
    );
  });

  it('所有认证端点都启用同源 Cookie，业务客户端只返回 data', () => {
    expect(requestClient).toBeDefined();
    expect(baseRequestClient).toBeDefined();
    expect(mocks.clientInstances).toHaveLength(2);
    expect(mocks.clientInstances[0]?.options).toEqual({
      baseURL: '/api',
      responseReturn: 'data',
      withCredentials: true,
    });
    expect(mocks.clientInstances[1]?.options).toEqual({
      baseURL: '/api',
      withCredentials: true,
    });
  });

  it('请求头仅使用内存 access token 并携带当前语言', async () => {
    mocks.accessStore.accessToken = 'access-token';
    const config = { headers: {}, url: '/system/user' };

    await expect(mocks.requestInterceptors[0]?.fulfilled(config)).resolves.toBe(
      config,
    );
    expect(config.headers).toEqual({
      'Accept-Language': 'zh-CN',
      Authorization: 'Bearer access-token',
    });
  });

  it('刷新成功只保存新 access token，响应缺失 token 时抛稳定错误', async () => {
    mocks.accessStore.accessToken = 'previous';
    vi.mocked(refreshTokenApi).mockResolvedValue({
      data: { data: { accessToken: 'new-access-token' } },
    } as never);

    await expect(mocks.authenticateOptions?.doRefreshToken()).resolves.toBe(
      'new-access-token',
    );
    expect(mocks.accessStore.setAccessToken).toHaveBeenCalledWith(
      'new-access-token',
    );

    vi.mocked(refreshTokenApi).mockResolvedValue({
      data: { data: {} },
    } as never);
    await expect(mocks.authenticateOptions?.doRefreshToken()).rejects.toThrow(
      'Refresh token response did not include an access token',
    );
  });

  it('会话失效时清除 token，并按配置显示过期弹窗', async () => {
    mocks.accessStore.accessToken = 'expired-token';
    mocks.accessStore.isAccessChecked = true;

    await mocks.authenticateOptions?.doReAuthenticate();

    expect(mocks.logWarn).toHaveBeenCalledWith('Authentication expired');
    expect(mocks.accessStore.setAccessToken).toHaveBeenCalledWith(null);
    expect(mocks.accessStore.setLoginExpired).toHaveBeenCalledWith(true);
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it('尚未建立登录状态时会话失效返回登录页', async () => {
    await mocks.authenticateOptions?.doReAuthenticate();
    expect(mocks.logout).toHaveBeenCalledOnce();
  });

  it('匿名请求不携带认证令牌', async () => {
    const config = { headers: {} };
    await mocks.requestInterceptors[0]?.fulfilled(config);
    expect(config.headers).toMatchObject({ Authorization: null });
  });

  it('跨源绝对 URL 请求剥离凭据，同源绝对 URL 正常附带', async () => {
    mocks.accessStore.accessToken = 'access-token';
    const crossOrigin = {
      headers: { Authorization: 'Bearer access-token' } as Record<
        string,
        string
      >,
      url: 'https://s3.example.com/private/object',
      withCredentials: true,
    };

    await mocks.requestInterceptors[0]?.fulfilled(crossOrigin);

    expect(crossOrigin.headers.Authorization).toBeUndefined();
    expect(crossOrigin.withCredentials).toBe(false);
    expect(crossOrigin.headers['Accept-Language']).toBe('zh-CN');

    const sameOrigin = {
      headers: {} as Record<string, string>,
      url: `${window.location.origin}/api/infra/file/4/get/a.pdf`,
    };

    await mocks.requestInterceptors[0]?.fulfilled(sameOrigin);

    expect(sameOrigin.headers.Authorization).toBe('Bearer access-token');
  });

  it('协议相对 URL 按 axios 语义视为绝对地址：跨源剥离凭据，同源附带', async () => {
    mocks.accessStore.accessToken = 'access-token';
    const protocolRelativeCross = {
      headers: { Authorization: 'Bearer access-token' } as Record<
        string,
        string
      >,
      url: '//evil.example.com/track',
    };

    await mocks.requestInterceptors[0]?.fulfilled(protocolRelativeCross);

    expect(protocolRelativeCross.headers.Authorization).toBeUndefined();

    const protocolRelativeSame = {
      headers: {} as Record<string, string>,
      url: `//${window.location.host}/api/infra/file/4/get/a.pdf`,
    };

    await mocks.requestInterceptors[0]?.fulfilled(protocolRelativeSame);

    expect(protocolRelativeSame.headers.Authorization).toBe(
      'Bearer access-token',
    );
  });

  it('反斜杠 URL 形态归一后按绝对地址处理：跨源剥离凭据', async () => {
    mocks.accessStore.accessToken = 'access-token';
    // WHATWG 在 special scheme 下把 \ 视同 /：\\evil.example.com\track 会被解析为跨域地址
    const backslashCross = {
      headers: { Authorization: 'Bearer access-token' } as Record<
        string,
        string
      >,
      url: String.raw`\\evil.example.com\track`,
    };

    await mocks.requestInterceptors[0]?.fulfilled(backslashCross);

    expect(backslashCross.headers.Authorization).toBeUndefined();
  });

  it('刷新后的重试在发送前再次阻止跨账号身份切换', async () => {
    mocks.accessStore.accessToken = 'new-account';
    const config = {
      __isRetryRequest: true,
      headers: { Authorization: 'Bearer previous' },
    };
    await expect(
      mocks.requestInterceptors[0]?.fulfilled(config),
    ).rejects.toThrow('Authentication changed before retrying the request');
    expect(config.headers.Authorization).toBe('Bearer previous');
  });

  it('外部来源的 401 不进入真实认证拦截器的刷新、队列或登出分支', async () => {
    const client = requestClient;
    for (const url of [
      'https://files.example.com/a.pdf',
      '//files.example.com/a.pdf',
    ]) {
      for (const retry of [false, true]) {
        const error = {
          config: { url, headers: {}, __isRetryRequest: retry },
          response: { status: 401 },
        };
        const doRefreshToken = vi.fn();
        const doReAuthenticate = vi.fn();
        const interceptor = authenticateResponseInterceptor({
          client,
          doRefreshToken,
          doReAuthenticate,
          enableRefreshToken: true,
          formatToken: (token) => token,
          shouldAuthenticate: mocks.authenticateOptions?.shouldAuthenticate,
        });
        await expect(interceptor.rejected?.(error)).rejects.toBe(error);
        expect(doRefreshToken).not.toHaveBeenCalled();
        expect(doReAuthenticate).not.toHaveBeenCalled();
      }
    }
    expect(
      mocks.authenticateOptions?.shouldAuthenticate({
        config: { url: '/system/user' },
      }),
    ).toBe(true);
    expect(mocks.authenticateOptions?.shouldAuthenticate({})).toBe(true);
  });

  it('匿名请求的 401 不会重新恢复已退出的会话', async () => {
    await expect(mocks.authenticateOptions?.doRefreshToken()).rejects.toThrow(
      'No active session to refresh',
    );
    expect(refreshTokenApi).not.toHaveBeenCalled();
  });

  it('旧账号请求的 401 不会刷新新账号会话并重放旧请求', async () => {
    mocks.accessStore.accessToken = 'new-account';
    await expect(
      mocks.authenticateOptions?.doRefreshToken({
        config: { headers: { Authorization: 'Bearer previous' } },
      }),
    ).rejects.toThrow('Authentication changed before refreshing the session');
    expect(refreshTokenApi).not.toHaveBeenCalled();
    expect(mocks.accessStore.accessToken).toBe('new-account');
  });

  it('最终错误交给统一反馈', () => {
    const responseError = new Error('safe error');
    mocks.errorMessageCallback?.('请求失败', responseError);
    expect(mocks.showRequestError).toHaveBeenCalledWith(
      responseError,
      '请求失败',
    );
  });

  it('discards a refresh response after the active account changes', async () => {
    mocks.accessStore.accessToken = 'previous';
    let finish!: (value: never) => void;
    vi.mocked(refreshTokenApi).mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const refresh = mocks.authenticateOptions?.doRefreshToken();
    mocks.accessStore.accessToken = 'new-account';
    finish({ data: { data: { accessToken: 'obsolete' } } } as never);
    await expect(refresh).rejects.toThrow(
      'Authentication changed while refreshing the session',
    );
    expect(mocks.accessStore.accessToken).toBe('new-account');
  });

  it('does not expire a new login because an old request returned 401', async () => {
    mocks.accessStore.accessToken = 'new-account';
    await mocks.authenticateOptions?.doReAuthenticate({
      config: {
        headers: { Authorization: 'Bearer previous' },
      },
    });
    expect(mocks.accessStore.accessToken).toBe('new-account');
    expect(mocks.logout).not.toHaveBeenCalled();
    expect(mocks.accessStore.setLoginExpired).not.toHaveBeenCalled();
  });
});
