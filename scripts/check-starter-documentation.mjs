import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const CORE_PATH = join(
  REPO_ROOT,
  '后端代码',
  'basic-framework-boot',
  'basic-framework-core',
);
const STARTER_NAME_PATTERN = /^basic-framework-spring-boot-starter-/;

export function starterDocumentationFailures(corePath) {
  if (!existsSync(corePath)) {
    return [`core 模块目录不存在：${corePath}`];
  }

  const failures = [];
  const starters = readdirSync(corePath, { withFileTypes: true })
    .filter(
      (entry) => entry.isDirectory() && STARTER_NAME_PATTERN.test(entry.name),
    )
    .sort((left, right) => left.name.localeCompare(right.name));

  for (const starter of starters) {
    const modulePath = join(corePath, starter.name);
    const pomPath = join(modulePath, 'pom.xml');
    const readmePath = join(modulePath, 'README.md');
    if (!existsSync(pomPath)) {
      failures.push(`${starter.name} 缺少 pom.xml`);
      continue;
    }
    if (!existsSync(readmePath)) {
      failures.push(`${starter.name} 缺少 README.md`);
      continue;
    }
    const content = readFileSync(readmePath, 'utf8');
    if (!/^#\s+\S+/m.test(content)) {
      failures.push(`${starter.name}/README.md 缺少一级标题`);
    }
    if (!/^##\s+\S+/m.test(content)) {
      failures.push(`${starter.name}/README.md 缺少能力或约束章节`);
    }
    const headingLines = content
      .split(/\r?\n/)
      .filter((line) => /^#{1,2}\s+\S+/.test(line.trim()));
    const duplicates = headingLines.filter(
      (line, index) => headingLines.indexOf(line) !== index,
    );
    if (duplicates.length > 0) {
      failures.push(
        `${starter.name}/README.md 存在重复章节标题：${[...new Set(duplicates)].join('、')}`,
      );
    }
    const bodyWithoutHeadings = content
      .split(/\r?\n/)
      .map((line) => line.replace(/^#{1,6}\s+/, ''))
      .filter((line) => line.trim().length > 0)
      .join('\n');
    if (bodyWithoutHeadings.trim().length < 20) {
      failures.push(`${starter.name}/README.md 除标题外缺少实质性内容`);
    }
  }
  return failures;
}

function verify() {
  const failures = starterDocumentationFailures(CORE_PATH);
  if (failures.length > 0) {
    throw new Error(`starter 文档契约失败：\n- ${failures.join('\n- ')}`);
  }
  console.log('starter 文档契约通过：每个能力接缝均包含 README.md');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verify();
}
