import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { overridesPreferences } from './preferences';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..');

/** 读出 `.env` 文件里实际生效的键值对（跳过注释与空行）。 */
function readEnvKeys(file: string): Record<string, string> {
  const parsed: Record<string, string> = {};
  for (const raw of readFileSync(join(appDir, file), 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const at = line.indexOf('=');
    if (at <= 0) continue;
    parsed[line.slice(0, at).trim()] = line.slice(at + 1).trim();
  }
  return parsed;
}

/**
 * 这些键一旦缺失，运行时就会退化成框架默认值、`undefined` 键名，或某个安全开关静默失效。
 *
 * <p>取值与实际读取处一一对应，不是随手罗列：任何一处新增 `import.meta.env` 读取
 * 却没有落到这两个文件里，守卫就会红——当初那个缺陷正是从这条缝溜过去的。
 */
const REQUIRED_KEYS = [
  // 标题与偏好存储 namespace（缺失会让 localStorage 键变成 undefined-undefined-dev）
  'VITE_APP_TITLE',
  'VITE_APP_NAMESPACE',
  'VITE_APP_VERSION',
  // 接口基址：use-app-config 在模块顶层读取，缺失直接整页白屏
  'VITE_GLOB_API_URL',
  // 上传通道：缺失会静默退回服务端上传
  'VITE_UPLOAD_TYPE',
  // 路由模式
  'VITE_ROUTER_HISTORY',
  // 验证码开关：isCaptchaEnable() 判定 `=== 'true'`，留空等于静默关闭
  'VITE_APP_CAPTCHA_ENABLE',
] as const;

describe('application preference overrides', () => {
  it('keeps backend routing, refresh rotation and product branding enabled', () => {
    expect(overridesPreferences).toMatchObject({
      app: {
        accessMode: 'backend',
        defaultHomePath: '/dashboard',
        enableRefreshToken: true,
      },
      copyright: {
        companySiteLink: '',
      },
      logo: {
        source: '/brand-logo.svg',
        sourceDark: '/brand-logo.svg',
      },
    });
  });

  /**
   * app 名与页脚公司名取自 `VITE_APP_TITLE`，且两者必须一致。
   *
   * <p>原断言写的是 `name: import.meta.env.VITE_APP_TITLE`——与实现里的表达式
   * 完全相同，等于把实现抄了一遍：env 配没配都过，实际是恒真断言。
   */
  it('app 名与页脚公司名同源，便于改一次配置就两处一起生效', () => {
    expect(overridesPreferences.app?.name).toBe(
      overridesPreferences.copyright?.companyName,
    );
  });

  /**
   * 直接校验 `.env` 文件本身，而不是校验 `import.meta.env`。
   *
   * <p>为什么不能只断言 `import.meta.env`：vitest 的 mode 是 `test`，
   * 而工作区根没有 `.env.test`，测试里 `VITE_APP_TITLE` 恒为 `undefined`——
   * 无论 `.env.development` 配没配都一样，测不出真实缺陷。
   * `.env` 曾漏配这三个键，标题退化成框架默认值、namespace 退化成
   * `undefined-undefined-dev`，而当时没有任何检查拦得住。
   *
   * <p>读文件才能覆盖开发与生产两条真实加载路径。
   */
  it.each([
    ['.env.development', '开发构建'],
    ['.env.production', '生产构建'],
  ])('%s（%s）必须显式配置标题与 namespace 相关键', (file) => {
    const env = readEnvKeys(file);
    for (const key of REQUIRED_KEYS) {
      expect(env[key], `${file} 缺少 ${key}`).toBeTruthy();
    }
  });
});
