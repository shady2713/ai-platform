import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';

const dockerfilePath = new URL(
  '../后端代码/basic-framework-boot/basic-framework-server/Dockerfile',
  import.meta.url,
);
const composePath = new URL('../后端代码/basic-framework-boot/docker-compose.yaml', import.meta.url);
const backendRoot = fileURLToPath(new URL('../后端代码/basic-framework-boot', import.meta.url));

const temurinImage =
  'eclipse-temurin:17.0.20_8-jre@sha256:13cc28a6cc72a38ce1f00c906be3580c1a3e604b8984d694f369a96742abc93b';
const mysqlImage =
  'mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb';
const redisImage =
  'redis:7.4.11@sha256:71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7';
// K01 候选向量索引：只在集成测试里启动，同样按 digest 固定（与 F02 台账锁定的版本一致）
const qdrantImage =
  'qdrant/qdrant:v1.19.1@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10';

test('production container images use explicit patch versions and immutable digests', async () => {
  const [dockerfile, compose] = await Promise.all([
    readFile(dockerfilePath, 'utf8'),
    readFile(composePath, 'utf8'),
  ]);
  const fromImages = [...dockerfile.matchAll(/^FROM\s+(\S+)/gm)].map((match) => match[1]);

  assert.deepEqual(fromImages, [temurinImage, temurinImage]);
  assert.match(
    dockerfile,
    /java -Djarmode=tools -jar app\.jar extract --layers --destination extracted/,
  );
  assert.doesNotMatch(dockerfile, /jarmode=layertools/);
  assert.match(dockerfile, /rm -f \/usr\/bin\/pebble/);
  assert.match(compose, new RegExp(`^\\s+image: ${escapeRegExp(mysqlImage)}$`, 'm'));
  assert.match(compose, new RegExp(`^\\s+image: ${escapeRegExp(redisImage)}$`, 'm'));
});

test('Testcontainers images match production and use immutable digests', async () => {
  const javaFiles = await findJavaFiles(backendRoot);
  const references = [];
  let compatibleMysqlReferences = 0;

  for (const file of javaFiles) {
    const source = await readFile(file, 'utf8');
    references.push(
      ...[...source.matchAll(/DockerImageName\.parse\(\s*"([^"]+)"\)/g)].map(
        (match) => match[1],
      ),
    );
    compatibleMysqlReferences += [
      ...source.matchAll(
        new RegExp(
          `DockerImageName\\.parse\\(\\s*"${escapeRegExp(mysqlImage)}"\\)\\s*\\.asCompatibleSubstituteFor\\("mysql"\\)`,
          'g',
        ),
      ),
    ].length;
  }

  assert.ok(references.length > 0, 'must discover Testcontainers image references');
  assert.deepEqual(new Set(references), new Set([mysqlImage, redisImage, qdrantImage]));
  assert.equal(
    compatibleMysqlReferences,
    references.filter((reference) => reference === mysqlImage).length,
  );
  // 向量索引镜像必须按 digest 固定（不允许可变标签）
  assert.match(
    references.find((reference) => reference.startsWith('qdrant/')),
    /@sha256:[0-9a-f]{64}$/,
  );
});

async function findJavaFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.name === 'target') continue;
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await findJavaFiles(absolutePath)));
    else if (entry.name.endsWith('.java')) files.push(absolutePath);
  }
  return files;
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
