import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const WORKSPACE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const WORKSPACE_FILE = join(WORKSPACE_ROOT, 'pnpm-workspace.yaml');
const TYPESCRIPT_COMMAND = 'tsc --noEmit';
const VUE_COMMAND = 'vue-tsc --noEmit --skipLibCheck';

export function parseWorkspacePatterns(content) {
  const patterns = [];
  let readingPackages = false;

  for (const line of content.split(/\r?\n/)) {
    if (line === 'packages:') {
      readingPackages = true;
      continue;
    }
    if (!readingPackages) continue;

    if (line.startsWith('  - ')) {
      const value = line.slice(4).trim();
      const quoted =
        (value.startsWith("'") && value.endsWith("'")) ||
        (value.startsWith('"') && value.endsWith('"'));
      patterns.push(quoted ? value.slice(1, -1) : value);
      continue;
    }
    if (/^\S/.test(line)) break;
  }

  return patterns;
}

export function expectedTypecheckCommand(hasVueSource) {
  return hasVueSource ? VUE_COMMAND : TYPESCRIPT_COMMAND;
}

export function typecheckContractFailures(packages) {
  return packages.flatMap(({ hasVueSource, name, typecheck }) => {
    const expected = expectedTypecheckCommand(hasVueSource);
    return typecheck === expected
      ? []
      : [
          `${name}: typecheck 应为 "${expected}"，当前为 ${JSON.stringify(typecheck)}`,
        ];
  });
}

function packageDirectories(pattern) {
  if (!pattern.endsWith('/*') || pattern.slice(0, -2).includes('*')) {
    throw new Error(`不支持的 workspace 目录模式：${pattern}`);
  }

  const parent = join(WORKSPACE_ROOT, pattern.slice(0, -2));
  if (!existsSync(parent)) return [];

  return readdirSync(parent, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => join(parent, entry.name));
}

function containsVueSource(directory) {
  if (!existsSync(directory)) return false;

  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const entryPath = join(directory, entry.name);
    if (entry.isDirectory() && containsVueSource(entryPath)) return true;
    if (entry.isFile() && entry.name.endsWith('.vue')) return true;
  }
  return false;
}

function discoverTypedPackages() {
  const patterns = parseWorkspacePatterns(readFileSync(WORKSPACE_FILE, 'utf8'));
  const directories = [
    ...new Set(patterns.flatMap((pattern) => packageDirectories(pattern))),
  ];

  return directories.flatMap((directory) => {
    const manifestPath = join(directory, 'package.json');
    if (
      !existsSync(manifestPath) ||
      !existsSync(join(directory, 'tsconfig.json'))
    ) {
      return [];
    }

    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    return [
      {
        hasVueSource: containsVueSource(join(directory, 'src')),
        name: manifest.name ?? directory,
        typecheck: manifest.scripts?.typecheck,
      },
    ];
  });
}

function verify() {
  const packages = discoverTypedPackages();
  const failures = typecheckContractFailures(packages);
  if (failures.length > 0) {
    throw new Error(`工作区类型检查契约失败：\n- ${failures.join('\n- ')}`);
  }
  console.log(
    `工作区类型检查契约通过：${packages.length} 个含 tsconfig 的包全部登记`,
  );
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  verify();
}
