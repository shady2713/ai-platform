import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import Vue from '@vitejs/plugin-vue';
import VueJsx from '@vitejs/plugin-vue-jsx';
import { configDefaults, defineConfig } from 'vitest/config';

interface CoverageExclusionContract {
  files: string[];
  version: number;
}

const coverageExclusionContract = JSON.parse(
  readFileSync(
    resolve(
      import.meta.dirname,
      '../../docs/contracts/frontend-coverage-exclusions.json',
    ),
    'utf8',
  ),
) as CoverageExclusionContract;

if (coverageExclusionContract.version !== 1) {
  throw new Error('Unsupported frontend coverage exclusion contract');
}

export default defineConfig({
  plugins: [Vue(), VueJsx()],
  test: {
    environment: 'happy-dom',
    exclude: [
      ...configDefaults.exclude,
      '**/e2e/**',
      '**/dist/**',
      '**/.{idea,git,cache,output,temp}/**',
      '**/node_modules/**',
      '**/{stylelint,eslint}.config.*',
      '.prettierrc.mjs',
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      include: [
        'apps/web-ele/src/**/*.{ts,tsx,vue}',
        'packages/**/src/**/*.{ts,tsx,vue}',
      ],
      exclude: [
        '**/__tests__/**',
        '**/*.{spec,test}.[tj]s?(x)',
        '**/*.d.ts',
        '**/generated/**',
        ...coverageExclusionContract.files,
        // Pure declarative SVG artwork has no script or behavioral branches.
        '**/icons/**/*.vue',
        '**/mock*/**',
        // entry assembly files: wiring only, no testable logic
        'apps/web-ele/src/main.ts',
        'apps/web-ele/src/bootstrap.ts',
      ],
      thresholds: {
        // Ratchet baseline measured on 2026-09-05 after the 0% debt settlement wave:
        //   lines 81.65% / statements 81.65% / functions 80.31% / branches 87.67%
        // Ratchet start: only allowed to go UP, never down.
        // When raising coverage, update these values in the same change.
        branches: 87.6,
        functions: 80.2,
        lines: 81,
        statements: 81,
      },
    },
  },
});
