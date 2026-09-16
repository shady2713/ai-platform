import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const workflowPath = new URL('../.github/workflows/verify.yml', import.meta.url);
const powershellHarnessPath = new URL('../.harness/verify.ps1', import.meta.url);
const shellHarnessPath = new URL('../.harness/verify.sh', import.meta.url);

const trivyImage =
  'ghcr.io/aquasecurity/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969';
const nvdCredentialName = ['NVD', 'API', 'KEY'].join('_');

function jobBlock(workflow, jobName, nextJobName) {
  const startMarker = `  ${jobName}:`;
  const endMarker = `\n  ${nextJobName}:`;
  const start = workflow.indexOf(startMarker);
  const end = workflow.indexOf(endMarker, start);
  assert.notEqual(start, -1, `缺少 ${jobName} job`);
  assert.notEqual(end, -1, `无法确定 ${jobName} job 边界`);
  return workflow.slice(start, end);
}

test('backend integration owns the backend coverage base comparison', async () => {
  const workflow = await readFile(workflowPath, 'utf8');
  const integrationJob = jobBlock(workflow, 'backend-integration', 'aggregate');

  assert.match(integrationJob, /fetch-depth:\s*0/);
  assert.match(integrationJob, /COVERAGE_BASE_SHA:/);
  assert.match(integrationJob, /verify\.sh integration/);
});

test('dependency vulnerability scanning is a blocking cross-platform gate', async () => {
  const [workflow, powershellHarness, shellHarness] = await Promise.all([
    readFile(workflowPath, 'utf8'),
    readFile(powershellHarnessPath, 'utf8'),
    readFile(shellHarnessPath, 'utf8'),
  ]);
  const dependencyJob = jobBlock(workflow, 'dependency-scan', 'backend-integration');
  const aggregateJob = workflow.slice(workflow.indexOf('  aggregate:'));

  assert.match(dependencyJob, /verify\.sh dependencies/);
  assert.match(aggregateJob, /- dependency-scan/);
  assert.match(aggregateJob, /needs\.dependency-scan\.result/);
  assert.match(aggregateJob, /test "\$DEPENDENCY_SCAN" = success/);

  for (const harness of [powershellHarness, shellHarness]) {
    assert.match(harness, /check-container-images\.test\.mjs/);
    assert.match(harness, /cyclonedx-maven-plugin:2\.9\.3:makeAggregateBom/);
    assert.match(harness, /includeTestScope=true/);
    assert.match(harness, /--cap-drop/);
    assert.match(harness, /no-new-privileges:true/);
    assert.match(harness, /readonly/);
    assert.match(harness, /--severity['", ]+HIGH,CRITICAL/);
    assert.match(harness, /--exit-code['", ]+1/);
    assert.match(harness, /--include-dev-deps/);
    assert.match(harness, /pnpm-lock\.yaml/);
    assert.match(harness, /target\/bom\.json/);
    assert.match(harness, /buildx['", ]+build/);
    assert.ok(harness.includes('basic-framework-server-image.tar'));
    assert.ok(harness.includes('trivy-image-report.txt'));
    assert.match(harness, /--input/);
    assert.match(harness, /['", ]image['", ]/);
    assert.match(harness, /['", ]config['", ]/);
    assert.ok(harness.includes(trivyImage), 'Trivy image must be pinned by version and digest');
    assert.ok(!harness.includes(nvdCredentialName), 'dependency scan must be keyless');
  }
});

test('source quality contract is blocking on both platforms', async () => {
  const [powershellHarness, shellHarness] = await Promise.all([
    readFile(powershellHarnessPath, 'utf8'),
    readFile(shellHarnessPath, 'utf8'),
  ]);

  for (const harness of [powershellHarness, shellHarness]) {
    assert.match(harness, /--test['", ]+scripts\/check-source-quality\.test\.mjs/);
    assert.match(harness, /node['", )]+.*scripts\/check-source-quality\.mjs/);
    assert.match(harness, /check-starter-documentation\.test\.mjs/);
    assert.match(harness, /node['", )]+.*scripts\/check-starter-documentation\.mjs/);
    assert.match(harness, /check-safe-exception-handling\.test\.mjs/);
    assert.match(harness, /node['", )]+.*scripts\/check-safe-exception-handling\.mjs/);
  }
});

test('controller boundary validation contract is blocking on both platforms', async () => {
  const [powershellHarness, shellHarness] = await Promise.all([
    readFile(powershellHarnessPath, 'utf8'),
    readFile(shellHarnessPath, 'utf8'),
  ]);

  for (const harness of [powershellHarness, shellHarness]) {
    assert.match(harness, /--test['", ]+scripts\/check-controller-validation\.test\.mjs/);
    assert.match(harness, /node['", )]+.*scripts\/check-controller-validation\.mjs/);
  }
});

test('database driver boundary is blocking on both platforms', async () => {
  const [powershellHarness, shellHarness] = await Promise.all([
    readFile(powershellHarnessPath, 'utf8'),
    readFile(shellHarnessPath, 'utf8'),
  ]);

  for (const harness of [powershellHarness, shellHarness]) {
    assert.match(harness, /check-database-driver-boundary\.test\.mjs/);
  }
});

test('removed code generation boundary is blocking on both platforms', async () => {
  const [powershellHarness, shellHarness] = await Promise.all([
    readFile(powershellHarnessPath, 'utf8'),
    readFile(shellHarnessPath, 'utf8'),
  ]);

  for (const harness of [powershellHarness, shellHarness]) {
    assert.match(harness, /check-removed-codegen-boundary\.test\.mjs/);
  }
});

test('management route naming boundary is blocking on both platforms', async () => {
  const [powershellHarness, shellHarness] = await Promise.all([
    readFile(powershellHarnessPath, 'utf8'),
    readFile(shellHarnessPath, 'utf8'),
  ]);

  for (const harness of [powershellHarness, shellHarness]) {
    assert.match(harness, /check-management-route-naming\.test\.mjs/);
  }
});
