/**
 * 产物压缩（X09）：对 `vite build` 的输出做一步 esbuild 压缩。
 *
 * <p>为什么需要单独一步：Vite 在 **ES 库模式**下有意保留空白（`minifyWhitespace: false`，
 * 见 vite 的 esbuild 构建插件），因此 `build.minify` 不会把空白与注释压掉。第三方宿主拿到的
 * 是运行期产物，压缩后体积明显更小（且不再夹带库源码里的 `eslint-disable` 注释文本）。
 *
 * <p>压缩只做 minify，不改模块结构：`import(...)` 动态分包与 `import.meta.url` 都保留
 * （元素靠它推导同目录的 `.css`）。压缩前后体积都会打印，交给 `verify:artifact` 计量与预算判定。
 *
 * 用法（由包的 `build` 脚本串联，不单独调用）：
 *   pnpm -F @vben/ai-web-component run build
 */
import { readdir, readFile, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { transform } from 'esbuild';

// 用 fileURLToPath 而不是 URL.pathname：路径含非 ASCII 字符时 pathname 是百分号编码的
const DIST_DIR = fileURLToPath(new URL('../dist/', import.meta.url));

function kb(bytes) {
  return Math.round((bytes / 1024) * 100) / 100;
}

async function minifyFile(name) {
  const path = join(DIST_DIR, name);
  const source = await readFile(path, 'utf8');
  const beforeStat = await stat(path);
  const before = beforeStat.size;
  const css = name.endsWith('.css');
  const result = await transform(source, {
    format: 'esm',
    legalComments: 'none',
    loader: css ? 'css' : 'js',
    minify: true,
    target: css ? undefined : 'es2020',
  });
  await writeFile(path, result.code);
  const afterStat = await stat(path);
  const after = afterStat.size;
  process.stdout.write(
    `  压缩 ${name}：${kb(before)} KiB → ${kb(after)} KiB（-${Math.round((1 - after / before) * 100)}%）\n`,
  );
}

const entries = await readdir(DIST_DIR);
const files = entries
  .filter((name) => name.endsWith('.js') || name.endsWith('.css'))
  .toSorted();
if (files.length === 0) {
  process.stderr.write('压缩失败：dist/ 下没有可压缩的 js/css 产物\n');
  process.exitCode = 1;
} else {
  for (const name of files) {
    await minifyFile(name);
  }
}
