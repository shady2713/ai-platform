import type { CSSOptions, UserConfig } from 'vite';

import type { DefineApplicationOptions } from '../typing.ts';

import path, { relative } from 'node:path';

import { defineConfig, mergeConfig } from 'vite';

import { loadApplicationPlugins } from '../plugins/index.ts';
import { loadAndConvertEnv } from '../utils/env.ts';
import { getCommonConfig } from './common.ts';

function defineApplicationConfig(userConfigPromise?: DefineApplicationOptions) {
  return defineConfig(async (config) => {
    const options = await userConfigPromise?.(config);
    const envConfig = await loadAndConvertEnv(process.cwd(), config.mode);
    const { base, port } = envConfig;
    const { command, mode } = config;
    const { application = {}, vite = {} } = options || {};
    const isBuild = command === 'build';

    const plugins = await loadApplicationPlugins({
      injectGlobalScss: true,
      isBuild,
      mode,
      root: process.cwd(),
      visualizer: envConfig.VITE_VISUALIZER,
      ...application,
    });

    const { injectGlobalScss = true } = application;
    const applicationConfig: UserConfig = {
      base,
      build: {
        rollupOptions: {
          output: {
            assetFileNames: '[ext]/[name]-[hash].[ext]',
            chunkFileNames: 'js/[name]-[hash].js',
            entryFileNames: 'js/[name]-[hash].js',
            manualChunks: splitVendorChunks,
          },
        },
        target: 'es2015',
      },
      css: createCssOptions(injectGlobalScss),
      esbuild: {
        drop: isBuild ? ['debugger'] : [],
        legalComments: 'none',
      },
      plugins,
      server: {
        host: true,
        port,
        warmup: {
          clientFiles: [
            './index.html',
            './src/bootstrap.ts',
            './src/{views,layouts,router,store,api,adapter}/*',
          ],
        },
      },
    };

    return mergeConfig(
      mergeConfig(await getCommonConfig(), applicationConfig),
      vite,
    );
  });
}

function findMonorepoRoot() {
  return path.resolve(__dirnameSafe(), '../../..');
}

const VENDOR_CHUNK_RULES: [chunkName: string, pattern: RegExp][] = [
  ['vue', /[\\/]node_modules[\\/](@vue|pinia|vue|vue-router)[\\/]/],
  ['element-plus', /[\\/]node_modules[\\/](@element-plus|element-plus)[\\/]/],
  ['vxe-table', /[\\/]node_modules[\\/]vxe-table[\\/]/],
  ['vxe-pc-ui', /[\\/]node_modules[\\/]vxe-pc-ui[\\/]/],
];

// Split heavy node_modules vendors into long-term cacheable chunks
function splitVendorChunks(id: string) {
  if (!id.includes('node_modules')) {
    return;
  }
  for (const [chunkName, pattern] of VENDOR_CHUNK_RULES) {
    if (pattern.test(id)) {
      return chunkName;
    }
  }
}

function __dirnameSafe() {
  return path.dirname(new URL(import.meta.url).pathname);
}

function createCssOptions(injectGlobalScss = true): CSSOptions {
  const root = findMonorepoRoot();
  return {
    preprocessorOptions: injectGlobalScss
      ? {
          scss: {
            additionalData: (content: string, filepath: string) => {
              const relativePath = relative(root, filepath);
              if (relativePath.startsWith(`apps${path.sep}`)) {
                return `@use "@vben/styles/global" as *;\n${content}`;
              }
              return content;
            },
          },
        }
      : {},
  };
}

export { defineApplicationConfig };
