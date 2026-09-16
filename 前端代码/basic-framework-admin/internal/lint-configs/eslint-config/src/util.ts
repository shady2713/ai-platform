import type { ESLint } from 'eslint';

export type Awaitable<T> = Promise<T> | T;

type InteropDefault<T> = T extends { default: infer U } ? U : T;

export async function interopDefault<T>(
  m: Awaitable<T>,
): Promise<InteropDefault<T>> {
  const resolved = await m;
  const defaultExport =
    typeof resolved === 'object' && resolved !== null && 'default' in resolved
      ? resolved.default
      : undefined;
  return (defaultExport || resolved) as InteropDefault<T>;
}

/** Normalizes third-party plugins whose bundled ESLint types target another minor API. */
export function toEslintPlugin(value: unknown): ESLint.Plugin {
  if (typeof value !== 'object' || value === null) {
    throw new TypeError('ESLint plugin must be an object');
  }
  return value as ESLint.Plugin;
}
