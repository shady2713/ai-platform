import { afterEach, describe, expect, it, vi } from 'vitest';

import { isCaptchaEnable, useAppConfig } from './use-app-config';

describe('useAppConfig', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    Reflect.deleteProperty(window, '_VBEN_ADMIN_PRO_APP_CONF_');
  });

  it('reads development configuration from the supplied environment', () => {
    expect(useAppConfig({ VITE_GLOB_API_URL: '/dev-api' }, false)).toEqual({
      apiURL: '/dev-api',
    });
  });

  it('rejects an invalid development API URL', () => {
    expect(() => useAppConfig({}, false)).toThrowError(
      'VITE_GLOB_API_URL 必须是字符串',
    );
  });

  it('reads production configuration from the injected global', () => {
    window._VBEN_ADMIN_PRO_APP_CONF_ = { VITE_GLOB_API_URL: '/prod-api' };

    expect(useAppConfig({}, true)).toEqual({ apiURL: '/prod-api' });
  });

  /**
   * 生产分支曾在守卫之前就解引用 `window._VBEN_ADMIN_PRO_APP_CONF_`：
   * 全局对象缺失时先抛 `Cannot read properties of undefined`，
   * 下面那句精心写的提示根本走不到；而本函数在 `api/request.ts` 模块顶层被调用，
   * 抛错即整页白屏，真实原因还被一句无关的 TypeError 掩盖。
   *
   * <p>触发条件是产物里 `_app.config.js` 存在但未执行（CDN 缓存旧 index.html、
   * 子路径部署、静态服务器漏配该文件）——构建与打包两道门都拦不住。
   */
  it('生产构建缺少注入全局时给出可操作的提示，而不是 undefined 读错误', () => {
    Reflect.deleteProperty(window, '_VBEN_ADMIN_PRO_APP_CONF_');

    expect(() => useAppConfig({}, true)).toThrowError(/_app\.config\.js/);
    // 关键：不能是那条会掩盖真实原因的 TypeError
    expect(() => useAppConfig({}, true)).not.toThrowError(
      /Cannot read properties of undefined/,
    );
  });

  it('注入全局存在但缺 API 基址时，提示指向基址本身', () => {
    window._VBEN_ADMIN_PRO_APP_CONF_ =
      {} as typeof window._VBEN_ADMIN_PRO_APP_CONF_;

    expect(() => useAppConfig({}, true)).toThrowError(
      'VITE_GLOB_API_URL 必须是字符串',
    );
  });

  it('注入全局被置为 null 时同样给出可操作提示', () => {
    Reflect.deleteProperty(window, '_VBEN_ADMIN_PRO_APP_CONF_');
    Object.defineProperty(window, '_VBEN_ADMIN_PRO_APP_CONF_', {
      configurable: true,
      value: null,
    });

    expect(() => useAppConfig({}, true)).toThrowError(/_app\.config\.js/);
  });

  it('enables captcha only for the explicit true value', () => {
    vi.stubEnv('VITE_APP_CAPTCHA_ENABLE', 'true');
    expect(isCaptchaEnable()).toBe(true);
    vi.stubEnv('VITE_APP_CAPTCHA_ENABLE', 'false');
    expect(isCaptchaEnable()).toBe(false);
  });
});
