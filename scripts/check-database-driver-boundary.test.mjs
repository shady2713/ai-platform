import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import test from 'node:test';

const backendRoot = new URL('../后端代码/basic-framework-boot/', import.meta.url);
const mybatisPomPath = new URL(
  '../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-mybatis/pom.xml',
  import.meta.url,
);
const forbiddenDriverArtifacts = [
  'DmJdbcDriver18',
  'kingbase8',
  'mssql-jdbc',
  'ojdbc8',
  'opengauss-jdbc',
  'postgresql',
  'taos-jdbcdriver',
];

async function readProjectPoms(directory) {
  const contents = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.name === 'target') {
      continue;
    }
    const path = new URL(`${entry.name}${entry.isDirectory() ? '/' : ''}`, directory);
    if (entry.isDirectory()) {
      contents.push(...(await readProjectPoms(path)));
    } else if (entry.name === 'pom.xml') {
      contents.push(await readFile(path, 'utf8'));
    }
  }
  return contents;
}

test('backend bundles only the verified MySQL JDBC driver', async () => {
  const allPoms = (await readProjectPoms(backendRoot)).join('\n');
  const mybatisPom = await readFile(mybatisPomPath, 'utf8');

  assert.match(mybatisPom, /<artifactId>mysql-connector-j<\/artifactId>/);
  for (const artifactId of forbiddenDriverArtifacts) {
    assert.ok(
      !allPoms.includes(`<artifactId>${artifactId}</artifactId>`),
      `${artifactId} must be supplied and integration-tested by the consuming application`,
    );
  }
});

test('MyBatis declares its Jackson runtime dependencies directly', async () => {
  const mybatisPom = await readFile(mybatisPomPath, 'utf8');

  assert.match(mybatisPom, /<artifactId>jackson-databind<\/artifactId>/);
  assert.match(mybatisPom, /<artifactId>jackson-datatype-jsr310<\/artifactId>/);
});
