import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ensureFile, outputJSON, readJSON } from '../fs';

describe('node file helpers', () => {
  let temporaryDirectory: string;

  beforeEach(async () => {
    temporaryDirectory = await mkdtemp(join(tmpdir(), 'vben-node-utils-'));
  });

  afterEach(async () => {
    await rm(temporaryDirectory, { force: true, recursive: true });
  });

  it('writes and reads structured JSON in a nested directory', async () => {
    const filePath = join(temporaryDirectory, 'nested', 'data.json');
    const value = { enabled: true, nested: { count: 2 } };

    await outputJSON(filePath, value);

    await expect(readJSON(filePath)).resolves.toEqual(value);
    await expect(readFile(filePath, 'utf8')).resolves.toBe(
      `${JSON.stringify(value, null, 2)}`,
    );
  });

  it('creates an empty file without truncating existing content', async () => {
    const filePath = join(temporaryDirectory, 'nested', 'keep.txt');

    await ensureFile(filePath);
    await outputJSON(filePath, { keep: true }, 0);
    await ensureFile(filePath);

    await expect(readFile(filePath, 'utf8')).resolves.toBe('{"keep":true}');
  });
});
