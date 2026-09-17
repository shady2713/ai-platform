import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import process from 'node:process';

import { describe, expect, it } from 'vitest';

import { parseResultBlocks, parseRunEvent, parseTheme } from '../index';

/**
 * 跨语言夹具测试：与 Java 侧读取同一份 docs/contracts/ai/samples。
 * 命名约定即契约：*.valid.json 必须接受，*.invalid.json 必须拒绝。
 */
function findSamplesDir(): string {
  let dir = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    const candidate = join(dir, 'docs/contracts/ai/samples');
    if (existsSync(candidate)) {
      return candidate;
    }
    dir = dirname(dir);
  }
  throw new Error('未找到 docs/contracts/ai/samples：测试必须能到达仓库根');
}

const SAMPLES_DIR = findSamplesDir();

function readSample(name: string): unknown {
  return JSON.parse(readFileSync(join(SAMPLES_DIR, name), 'utf8'));
}

function parseByPrefix(name: string, payload: unknown): unknown {
  if (name.startsWith('result-block')) {
    return parseResultBlocks([payload]);
  }
  if (name.startsWith('theme-tokens')) {
    return parseTheme(payload);
  }
  if (name.startsWith('run-event')) {
    return parseRunEvent(payload);
  }
  if (name.startsWith('query-plan') || name.startsWith('report-spec')) {
    return payload; // 设计契约 Schema 由文档校验脚本负责，本测试只做存在性核对
  }
  throw new Error(`未登记前缀的夹具：${name}`);
}

const samples = readdirSync(SAMPLES_DIR).filter((name) =>
  name.endsWith('.json'),
);

describe('docs/contracts/ai 跨语言夹具', () => {
  it('夹具目录可读且包含有效与无效样例', () => {
    expect(
      samples.filter((name) => name.endsWith('.valid.json')).length,
    ).toBeGreaterThan(3);
    expect(
      samples.filter((name) => name.endsWith('.invalid.json')).length,
    ).toBeGreaterThan(3);
  });

  it.each(samples.filter((name) => name.endsWith('.valid.json')))(
    '接受有效样例 %s',
    (name) => {
      expect(() => parseByPrefix(name, readSample(name))).not.toThrow();
    },
  );

  it.each(samples.filter((name) => name.endsWith('.invalid.json')))(
    '拒绝无效样例 %s',
    (name) => {
      expect(() => parseByPrefix(name, readSample(name))).toThrow();
    },
  );

  it('金额以十进制字符串保留精度', () => {
    const parsed = parseResultBlocks([
      readSample('result-block.chart-money.valid.json'),
    ]);

    const block = parsed[0];
    expect(block?.kind).toBe('chart');
    if (block?.kind !== 'chart') {
      throw new Error('夹具应当是 chart 块');
    }
    expect(block.spec.series[0]?.data).toEqual([
      '12345678901234.56',
      '98765432109876.54',
    ]);
  });
});
