import type { Linter } from 'eslint';

const restrictedImportIgnores = [
  '**/vite.config.mts',
  '**/tailwind.config.mjs',
  '**/postcss.config.mjs',
];

// apps 通用禁止导入模式（多个配置块共用，后者覆盖前者时需重复声明）
const restrictedImportPatterns = [
  {
    group: ['#/api/*'],
    message:
      'The #/api package cannot be imported, please use the @core package itself',
  },
  {
    group: ['#/layouts/*'],
    message:
      'The #/layouts package cannot be imported, please use the @core package itself',
  },
  {
    group: ['#/locales/*'],
    message:
      'The #/locales package cannot be imported, please use the @core package itself',
  },
  {
    group: ['#/stores/*'],
    message:
      'The #/stores package cannot be imported, please use the @core package itself',
  },
];

const customConfig: Linter.Config[] = [
  // shadcn-ui 内部组件是自动生成的，不做太多限制
  {
    files: ['packages/@core/ui-kit/shadcn-ui/**/**'],
    rules: {
      'vue/require-default-prop': 'off',
    },
  },
  {
    files: [
      'apps/**/**',
      'packages/effects/**/**',
      'packages/utils/**/**',
      'packages/types/**/**',
      'packages/locales/**/**',
    ],
    ignores: restrictedImportIgnores,
    rules: {
      'perfectionist/sort-interfaces': 'off',
      'perfectionist/sort-objects': 'off',
    },
  },
  {
    files: ['**/**.vue'],
    ignores: restrictedImportIgnores,
    rules: {
      'perfectionist/sort-objects': 'off',
    },
  },
  {
    // apps内部的一些基础规则
    files: ['apps/**/**'],
    ignores: restrictedImportIgnores,
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: restrictedImportPatterns,
        },
      ],
      'perfectionist/sort-interfaces': 'off',
    },
  },
  {
    // web-ele 反馈 API 统一收口：业务代码禁止直用 element-plus 的
    // ElMessage/ElMessageBox/ElNotification，一律走 #/utils/feedback 封装
    // （showSuccessMessage/showErrorMessage/showWarningMessage/
    // showConfirmDialog/showAlertDialog/showSuccessNotification），
    // 保证错误文案归一化与交互一致性；豁免文件见下方覆盖块
    files: ['apps/web-ele/src/**/**'],
    ignores: restrictedImportIgnores,
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: 'element-plus',
              importNames: ['ElMessage', 'ElMessageBox', 'ElNotification'],
              message:
                '请使用 #/utils/feedback 的统一反馈封装，不要直接导入 element-plus 反馈 API',
            },
          ],
          patterns: restrictedImportPatterns,
        },
      ],
    },
  },
  {
    // 豁免清单（恢复为 apps 通用 patterns，不含 element-plus 反馈限制）：
    // - utils/feedback.ts：统一反馈封装自身，是唯一直调出口
    // - adapter/component/index.ts：向 vben globalShareState 注册全局通知，
    //   属框架适配层胶水
    files: [
      'apps/web-ele/src/utils/feedback.ts',
      'apps/web-ele/src/adapter/component/index.ts',
    ],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: restrictedImportPatterns,
        },
      ],
    },
  },
  {
    files: ['packages/@core/**/**'],
    ignores: restrictedImportIgnores,
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@vben/*'],
              message:
                'The @core package cannot import the @vben package, please use the @core package itself',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['packages/@core/base/**/**'],
    ignores: restrictedImportIgnores,
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@vben/*', '@vben-core/*'],
              message:
                'The @vben-core/shared package cannot import the @vben package, please use the @core/shared package itself',
            },
          ],
        },
      ],
    },
  },

  {
    files: [
      'packages/types/**/**',
      'packages/utils/**/**',
      'packages/icons/**/**',
      'packages/constants/**/**',
      'packages/styles/**/**',
      'packages/stores/**/**',
      'packages/preferences/**/**',
      'packages/locales/**/**',
    ],
    ignores: restrictedImportIgnores,
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@vben/*'],
              message:
                'The @vben package cannot be imported, please use the @core package itself',
            },
          ],
        },
      ],
    },
  },
  // 后端模拟代码，不需要太多规则
  {
    files: ['docs/**/**'],
    rules: {
      '@typescript-eslint/no-extraneous-class': 'off',
      'n/no-extraneous-import': 'off',
      'n/prefer-global/buffer': 'off',
      'n/prefer-global/process': 'off',
      'no-console': 'off',
      'unicorn/prefer-module': 'off',
    },
  },
  {
    files: ['internal/**/**', 'scripts/**/**'],
    rules: {
      'no-console': 'off',
    },
  },
];

export { customConfig };
