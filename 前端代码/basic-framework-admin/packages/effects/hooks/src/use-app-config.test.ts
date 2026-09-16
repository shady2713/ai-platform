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

  it('enables captcha only for the explicit true value', () => {
    vi.stubEnv('VITE_APP_CAPTCHA_ENABLE', 'true');
    expect(isCaptchaEnable()).toBe(true);
    vi.stubEnv('VITE_APP_CAPTCHA_ENABLE', 'false');
    expect(isCaptchaEnable()).toBe(false);
  });
});
