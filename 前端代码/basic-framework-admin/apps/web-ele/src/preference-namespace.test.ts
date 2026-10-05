import { describe, expect, it } from 'vitest';

import { resolvePreferenceNamespace } from './preference-namespace';

/**
 * 偏好存储 namespace 的回归测试。
 *
 * <p>为什么必须有这个测试：`.env` 文件曾漏配 `VITE_APP_NAMESPACE` 与
 * `VITE_APP_VERSION`，而这段拼装逻辑当时没有任何测试，于是 namespace 静默退化成
 * `undefined-undefined-dev`。功能不报错、页面正常，但 localStorage 键变成
 * `undefined-undefined-dev-preferences*`，"按项目与版本隔离偏好"的意图彻底失效。
 *
 * <p>这类缺陷只能靠断言「键里不含 undefined」来兜住——原样照抄实现的同义反复测试
 * 配没配都过，等于没测。
 */
describe('resolvePreferenceNamespace', () => {
  it('配置齐全时按「标识-版本-环境」拼出', () => {
    expect(resolvePreferenceNamespace('my-app', '1.2.3', false)).toBe(
      'my-app-1.2.3-dev',
    );
    expect(resolvePreferenceNamespace('my-app', '1.2.3', true)).toBe(
      'my-app-1.2.3-prod',
    );
  });

  it('标识缺失时退回默认值，不产出 undefined 段', () => {
    const namespace = resolvePreferenceNamespace(undefined, '1.0.0', false);
    expect(namespace).toBe('basic-framework-admin-1.0.0-dev');
    expect(namespace).not.toContain('undefined');
  });

  it('版本缺失时退回默认值，不产出 undefined 段', () => {
    const namespace = resolvePreferenceNamespace('my-app', undefined, true);
    expect(namespace).toBe('my-app-0.0.0-prod');
    expect(namespace).not.toContain('undefined');
  });

  it('两者都缺失时仍不产出 undefined 段', () => {
    const namespace = resolvePreferenceNamespace(undefined, undefined, false);
    expect(namespace).toBe('basic-framework-admin-0.0.0-dev');
    expect(namespace).not.toContain('undefined');
  });

  it('空白字符串等同缺失（env 文件里写了空值是最容易漏的情况）', () => {
    expect(resolvePreferenceNamespace('   ', '\t', false)).toBe(
      'basic-framework-admin-0.0.0-dev',
    );
  });

  it('dev 与 prod 必须产出不同的键，否则生产与本地的偏好会互相污染', () => {
    expect(resolvePreferenceNamespace('a', '1', false)).not.toBe(
      resolvePreferenceNamespace('a', '1', true),
    );
  });
});
