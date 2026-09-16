import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { starterDocumentationFailures } from './check-starter-documentation.mjs';

function createModule(root, name, { pom = true, readme } = {}) {
  const modulePath = join(root, name);
  mkdirSync(modulePath, { recursive: true });
  if (pom) {
    writeFileSync(join(modulePath, 'pom.xml'), '<project />\n');
  }
  if (readme !== undefined) {
    writeFileSync(join(modulePath, 'README.md'), readme);
  }
}

function temporaryCore(testContext) {
  const corePath = mkdtempSync(join(tmpdir(), 'starter-docs-'));
  testContext.after(() => rmSync(corePath, { recursive: true, force: true }));
  return corePath;
}

test('完整 starter 文档契约通过且忽略非 starter 模块', (testContext) => {
  const corePath = temporaryCore(testContext);
  createModule(corePath, 'basic-framework-spring-boot-starter-valid', {
    readme: '# valid\n\n## 能力\n\n提供请求保护能力的实质性说明文字，用于验证正文长度门槛。\n',
  });
  createModule(corePath, 'basic-framework-common');

  assert.deepEqual(starterDocumentationFailures(corePath), []);
});

test('缺失模块文件或结构化 README 时阻断', (testContext) => {
  const corePath = temporaryCore(testContext);
  createModule(corePath, 'basic-framework-spring-boot-starter-no-pom', {
    pom: false,
    readme: '# title\n\n## 能力\n',
  });
  createModule(corePath, 'basic-framework-spring-boot-starter-no-readme');
  createModule(corePath, 'basic-framework-spring-boot-starter-no-h1', {
    readme: '## 能力\n',
  });
  createModule(corePath, 'basic-framework-spring-boot-starter-no-h2', {
    readme: '# title\n',
  });
  createModule(corePath, 'basic-framework-spring-boot-starter-empty-body', {
    readme: '# title\n\n## 能力\n\n## 验证\n',
  });
  createModule(corePath, 'basic-framework-spring-boot-starter-duplicate-h2', {
    readme: '# title\n\n## 使用约束\n约束一\n\n## 使用约束\n约束二\n',
  });

  assert.deepEqual(starterDocumentationFailures(corePath), [
    'basic-framework-spring-boot-starter-duplicate-h2/README.md 存在重复章节标题：## 使用约束',
    'basic-framework-spring-boot-starter-empty-body/README.md 除标题外缺少实质性内容',
    'basic-framework-spring-boot-starter-no-h1/README.md 缺少一级标题',
    'basic-framework-spring-boot-starter-no-h1/README.md 除标题外缺少实质性内容',
    'basic-framework-spring-boot-starter-no-h2/README.md 缺少能力或约束章节',
    'basic-framework-spring-boot-starter-no-h2/README.md 除标题外缺少实质性内容',
    'basic-framework-spring-boot-starter-no-pom 缺少 pom.xml',
    'basic-framework-spring-boot-starter-no-readme 缺少 README.md',
  ]);
});

test('core 目录不存在时明确失败', () => {
  const missingPath = join(tmpdir(), `missing-core-${process.pid}-${Date.now()}`);

  assert.deepEqual(starterDocumentationFailures(missingPath), [
    `core 模块目录不存在：${missingPath}`,
  ]);
});
