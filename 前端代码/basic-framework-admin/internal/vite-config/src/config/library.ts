import type { ConfigEnv, UserConfig } from 'vite';

import type { DefineLibraryOptions } from '../typing.ts';

import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

import { defineConfig, mergeConfig } from 'vite';

import { loadLibraryPlugins } from '../plugins/index.ts';
import { getCommonConfig } from './common.ts';

function defineLibraryConfig(userConfigPromise?: DefineLibraryOptions) {
  return defineConfig(async (config: ConfigEnv) => {
    const options = await userConfigPromise?.(config);
    const { command, mode } = config;
    const { library = {}, vite = {} } = options || {};
    const isBuild = command === 'build';

    const plugins = await loadLibraryPlugins({
      isBuild,
      mode,
      ...library,
    });

    const pkg = await readLocalPackageJson(process.cwd());
    const externalPackages = [
      ...Object.keys(pkg.dependencies || {}),
      ...Object.keys(pkg.peerDependencies || {}),
    ];

    const packageConfig: UserConfig = {
      build: {
        lib: {
          entry: 'src/index.ts',
          fileName: () => 'index.mjs',
          formats: ['es'],
        },
        rollupOptions: {
          external: externalPackages,
        },
        sourcemap: false,
        target: 'es2018',
      },
      plugins,
    };

    return mergeConfig(
      mergeConfig(await getCommonConfig(), packageConfig),
      vite,
    );
  });
}

async function readLocalPackageJson(root: string) {
  const content = await readFile(join(root, 'package.json'), 'utf8');
  return JSON.parse(content) as {
    dependencies?: Record<string, string>;
    peerDependencies?: Record<string, string>;
  };
}

export { defineLibraryConfig };
