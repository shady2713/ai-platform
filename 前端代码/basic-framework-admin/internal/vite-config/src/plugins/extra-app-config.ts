import type { PluginOption } from 'vite';

import {
  colors,
  generatorContentHash,
  readPackageJSON,
} from '@vben/node-utils';

import { loadEnv } from '../utils/env.ts';

interface PluginOptions {
  isBuild: boolean;
  root: string;
}

const GLOBAL_CONFIG_FILE_NAME = '_app.config.js';
const VBEN_ADMIN_PRO_APP_CONF = '_VBEN_ADMIN_PRO_APP_CONF_';
const PUBLIC_RUNTIME_PREFIX = 'VITE_GLOB_';
const REQUIRED_RUNTIME_KEYS = ['VITE_GLOB_API_URL'] as const;

/**
 * Extract runtime app config into a standalone asset and inject it into HTML.
 */
async function viteExtraAppConfigPlugin({
  isBuild,
  root,
}: PluginOptions): Promise<PluginOption | undefined> {
  let publicPath: string;
  let source: string;

  if (!isBuild) {
    return;
  }

  const { version = '' } = await readPackageJSON(root);

  return {
    async configResolved(config) {
      publicPath = ensureTrailingSlash(config.base);
      source = await getConfigSource(root, config.mode);
    },
    async generateBundle() {
      this.emitFile({
        fileName: GLOBAL_CONFIG_FILE_NAME,
        source,
        type: 'asset',
      });

      console.log(colors.cyan('configuration file is build successfully!'));
    },
    name: 'vite:extra-app-config',
    async transformIndexHtml(html) {
      const hash = `v=${version}-${generatorContentHash(source, 8)}`;
      const appConfigSrc = `${publicPath}${GLOBAL_CONFIG_FILE_NAME}?${hash}`;

      return {
        html,
        tags: [{ attrs: { src: appConfigSrc }, tag: 'script' }],
      };
    },
  };
}

async function getConfigSource(root: string, mode: string | undefined) {
  const config = selectPublicRuntimeConfig(await loadEnv(root, mode));
  const windowVariable = `window.${VBEN_ADMIN_PRO_APP_CONF}`;
  let source = `${windowVariable}=${JSON.stringify(config)};`;
  source += `
    Object.freeze(${windowVariable});
    Object.defineProperty(window, "${VBEN_ADMIN_PRO_APP_CONF}", {
      configurable: false,
      writable: false,
    });
  `.replaceAll(/\s/g, '');
  return source;
}

function selectPublicRuntimeConfig(env: Record<string, string>) {
  const config = Object.fromEntries(
    Object.entries(env).filter(([key]) =>
      key.startsWith(PUBLIC_RUNTIME_PREFIX),
    ),
  );
  for (const key of REQUIRED_RUNTIME_KEYS) {
    if (!config[key]?.trim()) {
      throw new TypeError(`Missing public runtime config: ${key}`);
    }
  }
  return config;
}

function ensureTrailingSlash(path: string) {
  return path.endsWith('/') ? path : `${path}/`;
}

export { selectPublicRuntimeConfig, viteExtraAppConfigPlugin };
