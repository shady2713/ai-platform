import { describe, expect, it } from 'vitest';

import { inspectBuiltJavaScript } from './check-built-javascript.mjs';

describe('production JavaScript validation', () => {
  it('rejects the published dependency missing its internal import', () => {
    const messages = inspectBuiltJavaScript(
      'export function fromPairs(pairs) { const result = {}; for (const [key, value] of pairs) baseAssignValue(result, key, value); return result; }',
    );

    expect(messages).toEqual([
      expect.objectContaining({
        message: "'baseAssignValue' is not defined.",
        ruleId: 'no-undef',
        severity: 2,
      }),
    ]);
  });

  it('accepts resolved module imports and browser APIs', () => {
    expect(
      inspectBuiltJavaScript(
        "import assign from './assign.js'; const result = {}; assign(result, 'key', document.title); export default result;",
      ),
    ).toEqual([]);
  });

  it('accepts universal dependency environment probes', () => {
    expect(
      inspectBuiltJavaScript(
        "export const root = typeof global === 'object' ? global : globalThis; export const worker = typeof WorkerGlobalScope !== 'undefined';",
      ),
    ).toEqual([]);
  });

  it('rejects malformed scripts and ignores dependency lint bypasses', () => {
    expect(inspectBuiltJavaScript('export const broken = ;')).toEqual([
      expect.objectContaining({ fatal: true, severity: 2 }),
    ]);
    expect(
      inspectBuiltJavaScript('/* eslint-disable no-undef */\nmissingImport();'),
    ).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ ruleId: 'no-undef', severity: 2 }),
      ]),
    );
  });
});
