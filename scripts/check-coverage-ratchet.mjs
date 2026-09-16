import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizePath, findFilesBySuffix } from './gate-utils.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BASELINE_RELATIVE_PATH = 'docs/contracts/coverage-baseline.json';
const BASELINE_PATH = join(REPO_ROOT, BASELINE_RELATIVE_PATH);
const FRONTEND_EXCLUSIONS_PATH = join(
  REPO_ROOT,
  'docs/contracts/frontend-coverage-exclusions.json',
);
const FRONTEND_RELATIVE_ROOT = '前端代码/basic-framework-admin';
const FRONTEND_REPORT_PATH = join(
  REPO_ROOT,
  '前端代码/basic-framework-admin/coverage/coverage-summary.json',
);
const BACKEND_ROOT = join(REPO_ROOT, '后端代码/basic-framework-boot');

// basic-framework-server 依赖全部模块，其 report-aggregate 产物是后端唯一权威覆盖率来源。
const BACKEND_AGGREGATE_REPORT_PATH = join(
  BACKEND_ROOT,
  'basic-framework-coverage/target/site/jacoco-aggregate/jacoco.xml',
);
const STACKS = ['backend', 'frontend'];
const DEFAULT_NEW_FILE_MINIMUM = 80;
const EPSILON = 0.001;

function readFrontendExclusions() {
  const contract = JSON.parse(readFileSync(FRONTEND_EXCLUSIONS_PATH, 'utf8'));
  if (
    contract.version !== 1 ||
    !Array.isArray(contract.files) ||
    contract.files.some((sourcePath) => typeof sourcePath !== 'string')
  ) {
    throw new Error('frontend-coverage-exclusions.json 结构无效');
  }
  return new Set(
    contract.files.map((sourcePath) =>
      normalizePath(join(FRONTEND_RELATIVE_ROOT, sourcePath)),
    ),
  );
}

const FRONTEND_EXCLUSIONS = readFrontendExclusions();

export function coveragePercent(covered, total) {
  return total === 0 ? 100 : Math.round((covered / total) * 10_000) / 100;
}

function findFiles(root, fileName) {
  if (!existsSync(root)) {
    return [];
  }
  const result = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const target = join(root, entry.name);
    if (entry.isDirectory()) {
      result.push(...findFiles(target, fileName));
    } else if (entry.isFile() && entry.name === fileName) {
      result.push(target);
    }
  }
  return result;
}

function backendSourceModules() {
  return findFiles(BACKEND_ROOT, 'pom.xml')
    .map((pomPath) => {
      const moduleRoot = dirname(pomPath);
      const sourceRoot = join(moduleRoot, 'src', 'main', 'java');
      const sourcePaths = findFilesBySuffix(sourceRoot, '.java')
        .filter((sourcePath) => !sourcePath.endsWith('package-info.java'))
        .map((sourcePath) => normalizePath(relative(REPO_ROOT, sourcePath)));
      return {
        moduleRoot: normalizePath(relative(REPO_ROOT, moduleRoot)),
        sourcePaths,
      };
    })
    .filter((module) => module.sourcePaths.length > 0);
}

function parseBackendReport(reportPath) {
  const xml = readFileSync(reportPath, 'utf8');
  const reportMarker = join('target', 'site', 'jacoco', 'jacoco.xml');
  const moduleRoot = reportPath.slice(0, -(reportMarker.length + 1));
  const result = {};
  const packagePattern = /<package name="([^"]*)">([\s\S]*?)<\/package>/g;
  let packageMatch;
  while ((packageMatch = packagePattern.exec(xml)) !== null) {
    const packageName = packageMatch[1];
    const packageBody = packageMatch[2];
    const sourcePattern = /<sourcefile name="([^"]+)">([\s\S]*?)<\/sourcefile>/g;
    let sourceMatch;
    while ((sourceMatch = sourcePattern.exec(packageBody)) !== null) {
      const lineCounter = sourceMatch[2].match(
        /<counter type="LINE" missed="(\d+)" covered="(\d+)"\/>/,
      );
      if (lineCounter === null) {
        continue;
      }
      const sourcePath = join(
        moduleRoot,
        'src/main/java',
        packageName,
        sourceMatch[1],
      );
      if (!existsSync(sourcePath)) {
        continue;
      }
      const missed = Number(lineCounter[1]);
      const covered = Number(lineCounter[2]);
      result[normalizePath(relative(REPO_ROOT, sourcePath))] = coveragePercent(
        covered,
        missed + covered,
      );
    }
  }
  return {
    coverage: result,
    moduleRoot: normalizePath(relative(REPO_ROOT, moduleRoot)),
  };
}

export function addMissingBackendReportCoverage(
  coverage,
  sourceModules,
  reportedModuleRoots,
) {
  const result = { ...coverage };
  const unreportedSourcePaths = new Set();
  for (const module of sourceModules) {
    if (reportedModuleRoots.has(module.moduleRoot)) {
      continue;
    }
    for (const sourcePath of module.sourcePaths) {
      result[sourcePath] ??= 0;
      unreportedSourcePaths.add(sourcePath);
    }
  }
  return { coverage: result, unreportedSourcePaths };
}

/**
 * 聚合报告由 basic-framework-server 的 jacoco:report-aggregate 生成，
 * 合并全部模块的 jacoco.exec，因此被 server 集成测试执行、但不被所属模块
 * 单测执行的生产类能得到真实覆盖率，而不是被逐模块报告记成 0%（ADR 0045）。
 * 聚合报告只携带 package + 文件名，必须回查源码索引才能落到仓库路径。
 */
function backendSourceIndexByPackagePath(sourceModules) {
  const index = new Map();
  const marker = 'src/main/java/';
  for (const module of sourceModules) {
    for (const sourcePath of module.sourcePaths) {
      const position = sourcePath.indexOf(marker);
      if (position === -1) {
        continue;
      }
      index.set(sourcePath.slice(position + marker.length), sourcePath);
    }
  }
  return index;
}

function parseAggregateBackendReport(sourceModules) {
  const xml = readFileSync(BACKEND_AGGREGATE_REPORT_PATH, 'utf8');
  const index = backendSourceIndexByPackagePath(sourceModules);
  const result = {};
  const packagePattern = /<package name="([^"]*)">([\s\S]*?)<\/package>/g;
  let packageMatch;
  while ((packageMatch = packagePattern.exec(xml)) !== null) {
    const packageName = packageMatch[1];
    const sourcePattern = /<sourcefile name="([^"]+)">([\s\S]*?)<\/sourcefile>/g;
    let sourceMatch;
    while ((sourceMatch = sourcePattern.exec(packageMatch[2])) !== null) {
      const sourcePath = index.get(`${packageName}/${sourceMatch[1]}`);
      const lineCounter = sourceMatch[2].match(
        /<counter type="LINE" missed="(\d+)" covered="(\d+)"\/>/,
      );
      if (sourcePath === undefined || lineCounter === null) {
        continue;
      }
      result[sourcePath] = coveragePercent(
        Number(lineCounter[2]),
        Number(lineCounter[1]) + Number(lineCounter[2]),
      );
    }
  }
  return result;
}

function collectBackendCoverageDetails() {
  const sourceModules = backendSourceModules();
  if (existsSync(BACKEND_AGGREGATE_REPORT_PATH)) {
    const coverage = parseAggregateBackendReport(sourceModules);
    // ADR 0019 完整性兜底：整体缺席聚合报告的生产模块按 0% 计入，
    // 避免新增模块因未纳入 server 依赖而重新变成覆盖率盲区。
    const unreportedSourcePaths = new Set();
    for (const module of sourceModules) {
      const reported = module.sourcePaths.some((sourcePath) => sourcePath in coverage);
      if (reported) {
        continue;
      }
      for (const sourcePath of module.sourcePaths) {
        coverage[sourcePath] = 0;
        unreportedSourcePaths.add(sourcePath);
      }
    }
    return { coverage, unreportedSourcePaths };
  }
  const reports = findFiles(BACKEND_ROOT, 'jacoco.xml').filter((reportPath) =>
    normalizePath(reportPath).endsWith('/target/site/jacoco/jacoco.xml'),
  );
  if (reports.length === 0) {
    throw new Error('未找到 JaCoCo 报告；请先运行 backend gate');
  }
  const parsedReports = reports.map(parseBackendReport);
  return addMissingBackendReportCoverage(
    Object.assign({}, ...parsedReports.map((report) => report.coverage)),
    sourceModules,
    new Set(parsedReports.map((report) => report.moduleRoot)),
  );
}

function collectFrontendCoverage() {
  if (!existsSync(FRONTEND_REPORT_PATH)) {
    throw new Error('未找到前端覆盖率报告；请先运行 frontend gate');
  }
  const summary = JSON.parse(readFileSync(FRONTEND_REPORT_PATH, 'utf8'));
  const result = {};
  for (const [absolutePath, metrics] of Object.entries(summary)) {
    if (absolutePath === 'total') {
      continue;
    }
    const sourcePath = resolve(absolutePath);
    const repoRelativePath = normalizePath(relative(REPO_ROOT, sourcePath));
    if (repoRelativePath.startsWith('../') || !existsSync(sourcePath)) {
      continue;
    }
    result[repoRelativePath] = Number(metrics.lines.pct);
  }
  return result;
}

function collectCoverageDetails(stack) {
  if (stack === 'backend') {
    return collectBackendCoverageDetails();
  }
  return {
    coverage: collectFrontendCoverage(),
    unreportedSourcePaths: new Set(),
  };
}

function sortedRecord(record) {
  return Object.fromEntries(
    Object.entries(record).sort(([left], [right]) => left.localeCompare(right)),
  );
}

function sourceRequiresCoverage(sourcePath) {
  return (
    existsSync(join(REPO_ROOT, sourcePath)) &&
    !FRONTEND_EXCLUSIONS.has(normalizePath(sourcePath))
  );
}

export function coverageFailures(current, floors, exists = () => true) {
  const failures = [];
  for (const [sourcePath, floor] of Object.entries(floors)) {
    if (!exists(sourcePath)) {
      continue;
    }
    if (!(sourcePath in current)) {
      failures.push(`${sourcePath}: 覆盖率报告缺失`);
      continue;
    }
    if (current[sourcePath] + EPSILON < floor) {
      failures.push(
        `${sourcePath}: 当前 ${current[sourcePath]}% 低于基线 ${floor}%`,
      );
    }
  }
  for (const sourcePath of Object.keys(current)) {
    if (!(sourcePath in floors)) {
      failures.push(`${sourcePath}: 尚未登记单文件覆盖率基线`);
    }
  }
  return failures;
}

export function baselineFailures(
  candidate,
  previous,
  newFileMinimum,
  exists = () => true,
  existedAtBase = () => false,
) {
  const failures = [];
  for (const [sourcePath, oldFloor] of Object.entries(previous)) {
    if (!exists(sourcePath)) {
      continue;
    }
    if (!(sourcePath in candidate)) {
      failures.push(`${sourcePath}: 已登记的覆盖率基线被删除`);
    } else if (candidate[sourcePath] + EPSILON < oldFloor) {
      failures.push(
        `${sourcePath}: 基线从 ${oldFloor}% 下调为 ${candidate[sourcePath]}%`,
      );
    }
  }
  for (const [sourcePath, floor] of Object.entries(candidate)) {
    if (
      !(sourcePath in previous) &&
      !existedAtBase(sourcePath) &&
      floor + EPSILON < newFileMinimum
    ) {
      failures.push(
        `${sourcePath}: 新文件覆盖率 ${floor}% 低于 ${newFileMinimum}%`,
      );
    }
  }
  return failures;
}

function readBaseline(content = readFileSync(BASELINE_PATH, 'utf8')) {
  const baseline = JSON.parse(content);
  if (
    ![1, 2, 3].includes(baseline.version) ||
    !STACKS.every((stack) => baseline[stack])
  ) {
    throw new Error('coverage-baseline.json 结构无效');
  }
  if (baseline.version === 1) {
    return baseline;
  }
  if (
    baseline.version === 2 &&
    (!Array.isArray(baseline.backendLegacyUnreportedSources) ||
      baseline.backendLegacyUnreportedSources.some(
        (sourcePath) => typeof sourcePath !== 'string',
      ))
  ) {
    throw new Error('coverage-baseline.json 历史无报告源码台账无效');
  }
  if (
    baseline.version === 3 &&
    ('backendLegacyUnreportedSources' in baseline ||
      'backendContractOnlySources' in baseline)
  ) {
    throw new Error('coverage-baseline.json 版本 3 不允许覆盖率豁免清单');
  }
  return baseline;
}

function resolveBaseRef() {
  let baseRef = process.env.COVERAGE_BASE_SHA?.trim() || 'HEAD';
  if (/^0+$/.test(baseRef)) {
    baseRef = 'HEAD';
  }
  return baseRef;
}

function readBaseBaseline(baseRef) {
  try {
    const content = execFileSync(
      'git',
      ['show', `${baseRef}:${BASELINE_RELATIVE_PATH}`],
      { cwd: REPO_ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    );
    return readBaseline(content);
  } catch {
    return null;
  }
}

function sourceExistedAtBase(sourcePath, baseRef) {
  try {
    execFileSync('git', ['cat-file', '-e', baseRef + ':' + sourcePath], {
      cwd: REPO_ROOT,
      stdio: 'ignore',
    });
    return true;
  } catch {
    return false;
  }
}

function selectedStacks(stack) {
  return stack === 'all' ? STACKS : [stack];
}

function assertNoFailures(failures) {
  if (failures.length === 0) {
    return;
  }
  throw new Error(`覆盖率棘轮失败：\n- ${failures.join('\n- ')}`);
}

function verify(stack) {
  if (!existsSync(BASELINE_PATH)) {
    throw new Error('缺少 coverage-baseline.json；请生成初始基线');
  }
  const baseline = readBaseline();
  const baseRef = resolveBaseRef();
  const previous = readBaseBaseline(baseRef);
  const failures = [];
  for (const selected of selectedStacks(stack)) {
    const details = collectCoverageDetails(selected);
    const floors = baseline[selected];
    failures.push(
      ...coverageFailures(details.coverage, floors, sourceRequiresCoverage),
    );
    if (previous !== null) {
      failures.push(
        ...baselineFailures(
          baseline[selected],
          previous[selected],
          baseline.newFileMinimum,
          sourceRequiresCoverage,
          (sourcePath) => sourceExistedAtBase(sourcePath, baseRef),
        ),
      );
    }
  }
  assertNoFailures(failures);
  console.log(`coverage-ratchet: ${stack} 单文件基线通过`);
}

function update() {
  const previousFile = existsSync(BASELINE_PATH) ? readBaseline() : null;
  const baseRef = resolveBaseRef();
  const previousCommit = readBaseBaseline(baseRef);
  const baseline = {
    version: 3,
    newFileMinimum:
      previousFile?.newFileMinimum ?? DEFAULT_NEW_FILE_MINIMUM,
    backend: {},
    frontend: {},
  };
  const failures = [];
  for (const stack of STACKS) {
    const details = collectCoverageDetails(stack);
    const current = details.coverage;
    const existingFloors = previousFile?.[stack] ?? {};
    baseline[stack] = sortedRecord(
      Object.fromEntries(
        Object.entries(current).map(([sourcePath, percent]) => [
          sourcePath,
          Math.max(percent, existingFloors[sourcePath] ?? 0),
        ]),
      ),
    );
    failures.push(
      ...coverageFailures(
        details.coverage,
        baseline[stack],
        sourceRequiresCoverage,
      ),
    );
    if (previousCommit !== null) {
      failures.push(
        ...baselineFailures(
          baseline[stack],
          previousCommit[stack],
          baseline.newFileMinimum,
          sourceRequiresCoverage,
          (sourcePath) => sourceExistedAtBase(sourcePath, baseRef),
        ),
      );
    }
  }
  assertNoFailures(failures);
  writeFileSync(BASELINE_PATH, `${JSON.stringify(baseline, null, 2)}\n`);
  console.log('coverage-ratchet: 已更新单文件覆盖率基线');
}

function main() {
  const args = process.argv.slice(2);
  if (args.length === 1 && args[0] === '--update') {
    update();
    return;
  }
  const stack = args[0] ?? 'all';
  if (!['all', ...STACKS].includes(stack) || args.length > 1) {
    throw new Error(
      '用法: node scripts/check-coverage-ratchet.mjs [backend|frontend|all|--update]',
    );
  }
  verify(stack);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
