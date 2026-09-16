import type { PluginOption } from 'vite';

import type {
  ApplicationPluginOptions,
  CommonPluginOptions,
  ConditionPlugin,
  LibraryPluginOptions,
} from '../typing.ts';

import viteVue from '@vitejs/plugin-vue';
import viteVueJsx from '@vitejs/plugin-vue-jsx';

import { viteExtraAppConfigPlugin } from './extra-app-config.ts';

async function loadConditionPlugins(conditionPlugins: ConditionPlugin[]) {
  const plugins: PluginOption[] = [];
  for (const conditionPlugin of conditionPlugins) {
    if (conditionPlugin.condition) {
      const realPlugins = await conditionPlugin.plugins();
      plugins.push(...realPlugins);
    }
  }
  return plugins.flat();
}

async function loadCommonPlugins(
  options: CommonPluginOptions,
): Promise<ConditionPlugin[]> {
  return [
    {
      condition: true,
      plugins: () => [
        viteVue({
          script: {
            defineModel: true,
          },
        }),
        viteVueJsx(),
      ],
    },
    {
      condition:
        (options.isBuild ?? false) &&
        (options.visualizer ?? process.env.ANALYZE === 'true'),
      plugins: async () => {
        const { visualizer } = await import('rollup-plugin-visualizer');
        return [
          visualizer({
            brotliSize: true,
            filename: './node_modules/.cache/visualizer/stats.html',
            gzipSize: true,
            open: true,
          }) as PluginOption,
        ];
      },
    },
  ];
}

async function loadApplicationPlugins(
  options: ApplicationPluginOptions,
): Promise<PluginOption[]> {
  const plugins = await loadConditionPlugins(await loadCommonPlugins(options));
  const extraAppConfigPlugin = await viteExtraAppConfigPlugin({
    isBuild: options.isBuild ?? false,
    root: options.root ?? process.cwd(),
  });
  if (extraAppConfigPlugin) {
    plugins.push(extraAppConfigPlugin);
  }
  return plugins;
}

async function loadLibraryPlugins(
  options: LibraryPluginOptions,
): Promise<PluginOption[]> {
  return await loadConditionPlugins(await loadCommonPlugins(options));
}

export { loadApplicationPlugins, loadLibraryPlugins };
