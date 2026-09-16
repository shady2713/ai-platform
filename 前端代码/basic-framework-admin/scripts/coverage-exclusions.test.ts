import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

interface CoverageExclusionContract {
  files: string[];
  policy: string;
  version: number;
}

const WORKSPACE_ROOT = resolve(import.meta.dirname, '..');
const CONTRACT_PATH = resolve(
  WORKSPACE_ROOT,
  '../../docs/contracts/frontend-coverage-exclusions.json',
);

function readContract(): CoverageExclusionContract {
  return JSON.parse(
    readFileSync(CONTRACT_PATH, 'utf8'),
  ) as CoverageExclusionContract;
}

function runtimeJavaScript(source: string): string {
  return ts
    .transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.ESNext,
        target: ts.ScriptTarget.ES2022,
        verbatimModuleSyntax: false,
      },
    })
    .outputText.replaceAll(/^export \{\};?$/gm, '')
    .replaceAll(/^["']use strict["'];?$/gm, '')
    .trim();
}

describe('frontend coverage exclusions', () => {
  it('only excludes unique modules with no runtime JavaScript', () => {
    const contract = readContract();

    expect(contract.version).toBe(1);
    expect(contract.policy).toContain('no runtime JavaScript');
    expect(new Set(contract.files).size).toBe(contract.files.length);

    for (const sourcePath of contract.files) {
      const source = readFileSync(resolve(WORKSPACE_ROOT, sourcePath), 'utf8');
      expect(runtimeJavaScript(source), sourcePath).toBe('');
    }
  });
});
