/**
 * 偏好存储 namespace（localStorage key 前缀）的解析。
 *
 * <p>独立成文件而不是留在 `main.ts`：`main.ts` 被覆盖率配置列为
 * "entry assembly file: wiring only" 排除在统计之外，逻辑放那里等于不受棘轮约束。
 */

/** `VITE_APP_NAMESPACE` 缺失时使用；须与 `.env.*` 中的取值保持一致。 */
const FALLBACK_NAMESPACE = 'basic-framework-admin';

/** `VITE_APP_VERSION` 缺失时使用。 */
const FALLBACK_VERSION = '0.0.0';

/**
 * 拼出偏好存储的 namespace。
 *
 * <p>之所以对两段都做兜底：`.env` 文件曾漏配 `VITE_APP_NAMESPACE` 与 `VITE_APP_VERSION`，
 * 而拼装逻辑当时没有任何测试，于是 namespace 静默退化成 `undefined-undefined-dev`。
 * 功能不报错、页面正常，但 localStorage 键变成 `undefined-undefined-dev-preferences*`——
 * "按项目与版本隔离偏好"的意图彻底失效，且下次补配后旧键全部作废。
 * 宁可退回可读的默认值，也不要产出含 `undefined` 的键。
 *
 * @param namespaceValue `VITE_APP_NAMESPACE`，未配置或空白时为 undefined
 * @param versionValue `VITE_APP_VERSION`，未配置或空白时为 undefined
 * @param isProd 是否生产构建
 */
export function resolvePreferenceNamespace(
  namespaceValue: string | undefined,
  versionValue: string | undefined,
  isProd: boolean,
): string {
  const namespace = namespaceValue?.trim() || FALLBACK_NAMESPACE;
  const version = versionValue?.trim() || FALLBACK_VERSION;
  const env = isProd ? 'prod' : 'dev';
  return `${namespace}-${version}-${env}`;
}
