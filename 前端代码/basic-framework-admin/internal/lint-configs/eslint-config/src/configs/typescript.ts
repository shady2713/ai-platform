import type { Linter } from 'eslint';

import { interopDefault, toEslintPlugin } from '../util';

export async function typescript(): Promise<Linter.Config[]> {
  const [pluginTs, parserTs] = await Promise.all([
    interopDefault(import('@typescript-eslint/eslint-plugin')),
    interopDefault(import('@typescript-eslint/parser')),
  ] as const);

  const plugins = {
    '@typescript-eslint': toEslintPlugin(pluginTs),
  };

  const rules: Linter.RulesRecord = {
    ...pluginTs.configs['eslint-recommended']?.overrides?.[0]?.rules,
    ...pluginTs.configs.strict?.rules,
    '@typescript-eslint/ban-ts-comment': [
      'error',
      {
        'ts-check': false,
        'ts-expect-error': 'allow-with-description',
        'ts-ignore': 'allow-with-description',
        'ts-nocheck': 'allow-with-description',
      },
    ],

    // '@typescript-eslint/consistent-type-definitions': ['warn', 'interface'],
    '@typescript-eslint/consistent-type-definitions': 'off',
    '@typescript-eslint/explicit-function-return-type': 'off',
    '@typescript-eslint/explicit-module-boundary-types': 'off',
    '@typescript-eslint/no-empty-function': [
      'error',
      {
        allow: ['arrowFunctions', 'functions', 'methods'],
      },
    ],
    '@typescript-eslint/no-explicit-any': 'off',
    '@typescript-eslint/no-namespace': 'off',
    '@typescript-eslint/no-non-null-assertion': 'error',
    '@typescript-eslint/no-unused-expressions': 'off',
    '@typescript-eslint/no-unused-vars': [
      'error',
      {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
      },
    ],
    '@typescript-eslint/no-use-before-define': 'off',
    '@typescript-eslint/no-var-requires': 'error',
    'unused-imports/no-unused-vars': 'off',
  };

  return [
    {
      files: ['**/*.?([cm])[jt]s?(x)'],
      languageOptions: {
        parser: parserTs,
        parserOptions: {
          createDefaultProgram: false,
          ecmaFeatures: {
            jsx: true,
          },
          ecmaVersion: 'latest',
          extraFileExtensions: ['.vue'],
          jsxPragma: 'React',
          project: './tsconfig.*.json',
          sourceType: 'module',
        },
      },
      plugins,
      rules,
    },
    {
      // .vue 的 SFC 由 vue 配置的 vue-eslint-parser 解析（script 块走
      // @typescript-eslint/parser），此处仅叠加同一套规则
      files: ['**/*.vue'],
      plugins,
      rules,
    },
  ];
}
