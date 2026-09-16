import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { prettierFormat } from '../prettier';

describe('prettierFormat', () => {
  let temporaryDirectory: string;

  beforeEach(async () => {
    temporaryDirectory = await mkdtemp(join(tmpdir(), 'vben-prettier-'));
  });

  afterEach(async () => {
    await rm(temporaryDirectory, { force: true, recursive: true });
  });

  it('formats a supported file and persists the result', async () => {
    const filePath = join(temporaryDirectory, 'data.json');
    await writeFile(filePath, '{"enabled":true}', 'utf8');

    const output = await prettierFormat(filePath);

    expect(output).toBe('{ "enabled": true }\n');
    await expect(readFile(filePath, 'utf8')).resolves.toBe(output);
  });

  it('fails clearly when Prettier cannot infer a parser', async () => {
    const filePath = join(temporaryDirectory, 'unknown.extension');
    await writeFile(filePath, 'value', 'utf8');

    await expect(prettierFormat(filePath)).rejects.toThrow(
      `Cannot infer a Prettier parser for ${filePath}`,
    );
  });
});
