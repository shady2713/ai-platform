import type { ApplicationConfig } from '@vben/types/global';

/**
 * 由 vite-inject-app-config 注入的全局配置
 */
export function useAppConfig(
  env: Record<string, unknown>,
  isProduction: boolean,
): ApplicationConfig {
  const apiURL = isProduction
    ? window._VBEN_ADMIN_PRO_APP_CONF_.VITE_GLOB_API_URL
    : env.VITE_GLOB_API_URL;
  if (typeof apiURL !== 'string') {
    throw new TypeError('VITE_GLOB_API_URL 必须是字符串');
  }
  return {
    apiURL,
  };
}

export function isCaptchaEnable(): boolean {
  return import.meta.env.VITE_APP_CAPTCHA_ENABLE === 'true';
}
