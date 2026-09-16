import type { Plugin, PluginOption } from 'vite';

import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import { loadApplicationPlugins, loadLibraryPlugins } from './index';

describe('vite plugin boundaries', () => {
  let temporaryRoot: string | undefined;

  afterEach(async () => {
    if (temporaryRoot) {
      await rm(temporaryRoot, { force: true, recursive: true });
      temporaryRoot = undefined;
    }
  });

  it('adds browser runtime configuration only to application builds', async () => {
    temporaryRoot = await mkdtemp(join(tmpdir(), 'vben-vite-plugins-'));
    await writeFile(
      join(temporaryRoot, 'package.json'),
      JSON.stringify({ version: '1.0.0' }),
    );

    const applicationPlugins = await loadApplicationPlugins({
      isBuild: true,
      root: temporaryRoot,
    });
    const libraryPlugins = await loadLibraryPlugins({ isBuild: true });

    expect(
      applicationPlugins
        .filter((plugin) => isPlugin(plugin))
        .map((plugin) => plugin.name),
    ).toContain('vite:extra-app-config');
    expect(
      libraryPlugins
        .filter((plugin) => isPlugin(plugin))
        .map((plugin) => plugin.name),
    ).not.toContain('vite:extra-app-config');
  });
});

function isPlugin(plugin: PluginOption): plugin is Plugin {
  return Boolean(
    plugin &&
    !Array.isArray(plugin) &&
    typeof plugin === 'object' &&
    'name' in plugin,
  );
}
