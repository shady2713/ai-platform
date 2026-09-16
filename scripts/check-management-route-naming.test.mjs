import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import test from 'node:test';

const controllerRoots = [
  new URL(
    '../后端代码/basic-framework-boot/basic-framework-module-system/src/main/java/com/basicframework/module/system/controller/',
    import.meta.url,
  ),
  new URL(
    '../后端代码/basic-framework-boot/basic-framework-module-infra/src/main/java/com/basicframework/module/infra/controller/',
    import.meta.url,
  ),
];
const frontendApiRoot = new URL(
  '../前端代码/basic-framework-admin/apps/web-ele/src/api/',
  import.meta.url,
);

async function readTree(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = new URL(`${entry.name}${entry.isDirectory() ? '/' : ''}`, directory);
    if (entry.isDirectory()) {
      files.push(...(await readTree(path)));
    } else {
      files.push({ name: decodeURIComponent(path.pathname), text: await readFile(path, 'utf8') });
    }
  }
  return files;
}

function mappingPaths(source) {
  const paths = [];
  const annotation = /@(?:Get|Post|Put|Delete|Patch|Request)Mapping\s*\(([\s\S]*?)\)/g;
  for (const match of source.matchAll(annotation)) {
    for (const literal of match[1].matchAll(/["']([^"']+)["']/g)) {
      paths.push(literal[1]);
    }
  }
  return paths;
}

test('management routes start with slash, use kebab-case, and expose one canonical simple-list path', async () => {
  const controllerFiles = (await Promise.all(controllerRoots.map(readTree))).flat();

  for (const file of controllerFiles) {
    for (const path of mappingPaths(file.text)) {
      assert.ok(path.startsWith('/'), `route must start with /: ${path} in ${file.name}`);
      assert.ok(!path.includes('_'), `route must use kebab-case: ${path} in ${file.name}`);
      assert.notEqual(path, '/list-all-simple', `legacy simple-list alias reappeared in ${file.name}`);
      if (path.includes('simple-list')) {
        assert.equal(path, '/simple-list', `simple-list route is not canonical in ${file.name}`);
      }
    }
  }
});

test('frontend API clients do not call legacy management routes', async () => {
  const apiFiles = await readTree(frontendApiRoot);

  for (const file of apiFiles) {
    assert.doesNotMatch(file.text, /list-all-simple|\/[a-z0-9/-]*_[a-z0-9/_-]*/i, file.name);
  }
});
