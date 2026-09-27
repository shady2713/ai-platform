/**
 * Q08 兼容性回归切片（`tests/compatibility`）自用的只读工具。
 *
 * <p>约束：本目录只做**只读引用**——读取冻结夹具、构建产物、锁文件与工作区目录，
 * 不 import 任何产品代码的写路径，也不改动 `packages/**`。
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';

/** 前端工作区根（`pnpm-workspace.yaml` 所在目录）：从 cwd 向上查找，避免依赖调用方目录。 */
export function frontendRoot(): string {
  let directory = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    if (existsSync(join(directory, 'pnpm-workspace.yaml'))) {
      return directory;
    }
    directory = dirname(directory);
  }
  throw new Error('未找到前端工作区根（pnpm-workspace.yaml 不在任何上层目录）');
}

/** 仓库根（前端工作区位于 `前端代码/basic-framework-admin`）。 */
export function repositoryRoot(): string {
  return resolve(frontendRoot(), '..', '..');
}

/** 读取 JSON 文件；缺失或非法 JSON 直接抛错（不静默跳过）。 */
export function readJsonFile<T>(path: string): T {
  if (!existsSync(path)) {
    throw new Error(`缺少夹具/产物：${path}`);
  }
  return JSON.parse(readFileSync(path, 'utf8')) as T;
}

/** 读取文本文件；缺失直接抛错。 */
export function readTextFile(path: string): string {
  if (!existsSync(path)) {
    throw new Error(`缺少文件：${path}`);
  }
  return readFileSync(path, 'utf8');
}
