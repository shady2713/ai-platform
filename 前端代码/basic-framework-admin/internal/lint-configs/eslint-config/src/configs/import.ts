import type { Linter } from 'eslint';

import * as pluginImport from 'eslint-plugin-import-x';

export async function importPluginConfig(): Promise<Linter.Config[]> {
  return [
    {
      plugins: {
        // @ts-expect-error 动态导入后的插件模块类型与 ESLint 扁平配置声明不兼容
        import: pluginImport,
      },
      rules: {
        'import/consistent-type-specifier-style': ['error', 'prefer-top-level'],
        'import/first': 'error',
        'import/newline-after-import': 'error',
        'import/no-duplicates': 'error',
        'import/no-mutable-exports': 'error',
        'import/no-named-default': 'error',
        'import/no-self-import': 'error',
        'import/no-unresolved': 'off',
        'import/no-webpack-loader-syntax': 'error',
      },
    },
  ];
}
