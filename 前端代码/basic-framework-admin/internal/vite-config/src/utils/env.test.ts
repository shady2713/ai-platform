import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { loadAndConvertEnv, loadEnv } from './env';

describe('vite environment loading', () => {
  let root: string;

  beforeEach(async () => {
    root = await mkdtemp(join(tmpdir(), 'vben-vite-env-'));
  });

  afterEach(async () => {
    await rm(root, { force: true, recursive: true });
  });

  it('applies standard mode precedence and validates key names', async () => {
    await writeFile(
      join(root, '.env'),
      'VITE_GLOB_API_URL=/base\nINVALID KEY=ignored\n',
    );
    await writeFile(join(root, '.env.local'), 'VITE_GLOB_API_URL=/local\n');
    await writeFile(
      join(root, '.env.production'),
      'VITE_GLOB_API_URL=/production\n',
    );
    await writeFile(
      join(root, '.env.production.local'),
      'VITE_GLOB_API_URL="/production-local"\n',
    );

    await expect(loadEnv(root, 'production')).resolves.toEqual({
      VITE_GLOB_API_URL: '/production-local',
    });
  });

  it('converts validated application settings', async () => {
    await writeFile(
      join(root, '.env'),
      ['VITE_APP_TITLE=Admin', 'VITE_BASE=/console/', 'VITE_PORT=5174'].join(
        '\n',
      ),
    );

    await expect(loadAndConvertEnv(root)).resolves.toEqual(
      expect.objectContaining({
        VITE_APP_TITLE: 'Admin',
        base: '/console/',
        port: 5174,
      }),
    );
  });

  it.each([
    ['VITE_PORT=0', 'VITE_PORT 必须为 1-65535 的整数'],
    ['VITE_PORT=65536', 'VITE_PORT 必须为 1-65535 的整数'],
  ])(
    'rejects invalid deployment input without echoing it: %s',
    async (line, message) => {
      await writeFile(join(root, '.env'), line);

      await expect(loadAndConvertEnv(root)).rejects.toThrow(message);
    },
  );
});
