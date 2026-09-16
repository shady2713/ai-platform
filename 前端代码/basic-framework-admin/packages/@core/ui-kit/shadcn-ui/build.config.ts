import { defineBuildConfig } from 'unbuild';

const TEST_FILE_EXCLUSIONS = [
  '!**/__tests__/**',
  '!**/*.spec.*',
  '!**/*.test.*',
];

export default defineBuildConfig({
  clean: true,
  declaration: true,
  entries: [
    {
      builder: 'mkdist',
      input: './src',

      pattern: ['**/*', ...TEST_FILE_EXCLUSIONS],
    },
    {
      builder: 'mkdist',
      input: './src',
      loaders: ['vue'],
      pattern: ['**/*.vue', ...TEST_FILE_EXCLUSIONS],
    },
    {
      builder: 'mkdist',
      format: 'esm',
      input: './src',
      loaders: ['js'],
      pattern: ['**/*.ts', ...TEST_FILE_EXCLUSIONS],
    },
  ],
});
