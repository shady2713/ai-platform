import type { ApplicationConfig } from '@vben/types/global';

/**
 * 由 vite-inject-app-config 注入的全局配置
 */
export function useAppConfig(
  env: Record<string, unknown>,
  isProduction: boolean,
): ApplicationConfig {
  // 取值与守卫必须分开：直接写 `window._VBEN_ADMIN_PRO_APP_CONF_.VITE_GLOB_API_URL`
  // 会在全局对象缺失时先抛 `Cannot read properties of undefined`，
  // 下面那句精心写的提示根本走不到——而本函数在 `api/request.ts` 模块顶层被调用，
  // 抛错即整页白屏，且真实原因被一句无关的 TypeError 掩盖。
  // 产物里 `_app.config.js` 存在但未执行（CDN 缓存旧 index.html、子路径部署、
  // 静态服务器漏配该文件）时就会命中。
  //
  // 用 `?.` 而不是断言成 Record：全局已有 `VbenAdminProAppConfigRaw` 类型声明，
  // 断言反而会丢掉类型信息（并触发 TS2352）。
  const injected = isProduction ? window?._VBEN_ADMIN_PRO_APP_CONF_ : undefined;
  if (isProduction && (injected === null || typeof injected !== 'object')) {
    throw new TypeError(
      '生产构建缺少 window._VBEN_ADMIN_PRO_APP_CONF_：请确认 index.html 引入了 _app.config.js，且该脚本在应用启动前执行',
    );
  }
  const apiURL = isProduction
    ? injected?.VITE_GLOB_API_URL
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
