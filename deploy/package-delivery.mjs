#!/usr/bin/env node
/**
 * Q09 可复现交付包脚本：一条命令产出交付物清单。
 *
 * 产物组成（每个都来自实际构建或权威来源，脚本生成后逐项校验）：
 *   backend/basic-framework-server.jar   可执行 jar（Spring Boot repackage）
 *   backend/db-migration/                Flyway 迁移集（从 db/migration 复制，升级/排查用）
 *   frontend/admin/                      web-ele 生产构建产物（nginx 独立镜像的内容）
 *   frontend/chat/                       ai-chat 生产构建产物
 *   frontend/embed-assets/               嵌入页自托管产物（stage-embed-assets.mjs 输出，含清单）
 *   frontend/sdk/                        版本化嵌入 SDK 单文件产物
 *   sbom/backend-bom.json                CycloneDX 聚合 SBOM（Maven 解析结果，非手写）
 *   sbom/frontend-licenses.json          前端生产依赖许可清单（pnpm licenses list）
 *   NOTICE                               由上述两份清单聚合生成，不手写许可结论
 *   migrations/manifest.json|MIGRATIONS.md  从 db/migration 解析的迁移清单（含 sha256）
 *   config/app.env.example               配置模板（占位符，无任何真实凭据）
 *   MANIFEST.json / SHA256SUMS / version.json
 *
 * 用法（仓库根或任意目录均可执行）：
 *   node deploy/package-delivery.mjs [--output=<目录>] [--no-build] [--offline] [--skip-sbom]
 *
 *   --output     输出目录（默认 deploy/dist/<交付名>）
 *   --no-build   复用已有构建输出，不重新构建（仍做存在性与清单校验）
 *   --offline    Maven 使用离线模式（-o）构建后端；CycloneDX 插件要求在线模式，SBOM 步骤仍走在线 Maven
 *   --skip-sbom  跳过 SBOM 生成（仅用于依赖插件不可用的降级排查；判定为不完整交付）
 *
 * 幂等性：默认每次先删除并重建输出目录；产物只来自构建输出与权威文件，清单逐次重算。
 */
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = resolve(fileURLToPath(new URL('.', import.meta.url)));
const REPO_ROOT = resolve(SCRIPT_DIR, '..');
const BACKEND_DIR = join(REPO_ROOT, '后端代码', 'basic-framework-boot');
const FRONTEND_DIR = join(REPO_ROOT, '前端代码', 'basic-framework-admin');
const MIGRATION_DIR = join(
  BACKEND_DIR,
  'basic-framework-server',
  'src',
  'main',
  'resources',
  'db',
  'migration',
);
const SNAPSHOT_FILE = join(REPO_ROOT, '数据库文件', 'basic_framework.sql');
const SERVER_JAR = join(BACKEND_DIR, 'basic-framework-server', 'target', 'basic-framework-server.jar');
const AGGREGATE_BOM = join(BACKEND_DIR, 'target', 'bom.json');
const ADMIN_DIST = join(FRONTEND_DIR, 'apps', 'web-ele', 'dist');
const CHAT_DIST = join(FRONTEND_DIR, 'apps', 'ai-chat', 'dist');
const EMBED_STAGED = join(FRONTEND_DIR, 'apps', 'ai-chat', 'embed-assets');
const SDK_DIST = join(FRONTEND_DIR, 'packages', 'ai-embed-sdk', 'dist');
const CONFIG_TEMPLATE = join(SCRIPT_DIR, 'config', 'app.env.example');
/** 已发布基线版本：见 docs/ai-platform/01-framework-baseline.md（快照接管前的最高迁移号）。 */
const PUBLISHED_BASELINE_VERSION = 46;

const CYCLONEDX_GOAL = 'org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom';

function fail(message) {
  process.stderr.write(`package-delivery: ${message}\n`);
  process.exit(1);
}

function log(message) {
  process.stdout.write(`[delivery] ${message}\n`);
}

function argumentValue(name) {
  const prefix = `${name}=`;
  const inline = process.argv.find((argument) => argument.startsWith(prefix));
  if (inline !== undefined) {
    return inline.slice(prefix.length);
  }
  const index = process.argv.indexOf(name);
  return index === -1 ? undefined : process.argv[index + 1];
}

function hasFlag(name) {
  return process.argv.includes(name);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    stdio: options.stdio ?? 'inherit',
    encoding: 'utf8',
  });
  if (result.error) {
    fail(`${command} 无法执行：${result.error.message}`);
  }
  if (result.status !== 0) {
    fail(`${command} ${args.join(' ')} 退出码 ${result.status}`);
  }
  return result;
}

function output(command, args, options = {}) {
  return execFileSync(command, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    encoding: 'utf8',
  }).trim();
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function requireFile(file, label) {
  if (!existsSync(file) || !statSync(file).isFile()) {
    fail(`${label} 不存在：${relative(REPO_ROOT, file)}（先构建或去掉 --no-build）`);
  }
}

function requireNonEmptyDirectory(directory, label) {
  if (!existsSync(directory) || readdirSync(directory).length === 0) {
    fail(`${label} 为空或不存在：${relative(REPO_ROOT, directory)}（先构建或去掉 --no-build）`);
  }
}

/** 交付标识：后端 revision + git 短提交；dirty 工作树在原提交后加 -dirty。 */
function deliveryIdentity() {
  const backendPom = readFileSync(join(BACKEND_DIR, 'pom.xml'), 'utf8');
  const revision = /<revision>([^<]+)<\/revision>/.exec(backendPom)?.[1];
  if (revision === undefined) {
    fail('无法从后端 pom.xml 解析 <revision>');
  }
  let commit = 'nogit';
  let dirty = false;
  let branch = '';
  try {
    commit = output('git', ['-C', REPO_ROOT, 'rev-parse', '--short=12', 'HEAD']);
    branch = output('git', ['-C', REPO_ROOT, 'rev-parse', '--abbrev-ref', 'HEAD']);
    dirty = output('git', ['-C', REPO_ROOT, 'status', '--porcelain']).length > 0;
  } catch {
    // 无 git 元数据（源码归档）时仍可产出交付包，version.json 里如实标注 nogit。
  }
  const name = `basic-framework-ai-platform-${revision}-${commit}${dirty ? '-dirty' : ''}`;
  return { name, revision, commit, branch, dirty };
}

function packageVersion(relativePackageJson) {
  const parsed = JSON.parse(readFileSync(join(FRONTEND_DIR, relativePackageJson), 'utf8'));
  if (typeof parsed.version !== 'string') {
    fail(`${relativePackageJson} 缺少 version`);
  }
  return parsed.version;
}

/** 解析 db/migration 的版本化迁移，按数字版本排序（存在历史空洞，不能用数组下标当版本）。 */
function readMigrations() {
  const pattern = /^V(\d+)__(.+)\.sql$/;
  const entries = readdirSync(MIGRATION_DIR)
    .map((file) => {
      const match = pattern.exec(file);
      if (match === null) {
        fail(`db/migration 中存在不符合 V<数字>__<描述>.sql 命名的文件：${file}`);
      }
      return { version: Number(match[1]), description: match[2], file };
    })
    .sort((left, right) => left.version - right.version);
  if (entries.length === 0) {
    fail('db/migration 为空');
  }
  const seen = new Set();
  for (const entry of entries) {
    if (seen.has(entry.version)) {
      fail(`db/migration 存在重复版本号 V${entry.version}`);
    }
    seen.add(entry.version);
    const path = join(MIGRATION_DIR, entry.file);
    entry.bytes = statSync(path).size;
    entry.sha256 = sha256(path);
  }
  return entries;
}

/** 快照头部声明的 Flyway 版本；快照与迁移链必须一致（不一致即交付包不可信）。 */
function snapshotDeclaredVersion() {
  requireFile(SNAPSHOT_FILE, '数据库快照');
  const content = readFileSync(SNAPSHOT_FILE, 'utf8');
  const match = /-- Snapshot note: aligned with the authoritative Flyway migration chain through V(\d+)\./.exec(
    content,
  );
  if (match === null) {
    fail('数据库快照缺少 "Snapshot note: aligned with ... through V<版本>." 声明');
  }
  return Number(match[1]);
}

function buildBackend(offline) {
  log('构建后端 jar（-pl basic-framework-server -am -DskipTests package）');
  const args = ['-q'];
  if (offline) {
    args.push('-o');
  }
  args.push('-pl', 'basic-framework-server', '-am', '-DskipTests', 'package');
  run('./mvnw', args, { cwd: BACKEND_DIR });
  requireFile(SERVER_JAR, '可执行 jar');
}

function buildFrontend() {
  log('构建管理端产物（turbo --filter=@vben/web-ele）');
  run('pnpm', ['run', 'build', '--filter=@vben/web-ele'], { cwd: FRONTEND_DIR });
  log('构建 Chat 产物并生成嵌入产物（@vben/ai-chat build:embed）');
  run('pnpm', ['-F', '@vben/ai-chat', 'run', 'build:embed'], { cwd: FRONTEND_DIR });
  log('构建版本化 SDK 产物（@vben/ai-embed-sdk build）');
  run('pnpm', ['-F', '@vben/ai-embed-sdk', 'run', 'build'], { cwd: FRONTEND_DIR });
}

function buildSbom() {
  log('生成 CycloneDX 聚合 SBOM（Maven 解析结果）');
  // cyclonedx-maven-plugin 声明 requiresOnline=true，不能带 -o；离线环境只能 --skip-sbom。
  const args = [
    '-q',
    CYCLONEDX_GOAL,
    '-DskipTests',
    '-DoutputFormat=json',
    '-DincludeTestScope=true',
    '-DskipAttach=true',
  ];
  run('./mvnw', args, { cwd: BACKEND_DIR });
  requireFile(AGGREGATE_BOM, '聚合 SBOM');
}

/** 嵌入产物清单校验：清单声明与磁盘逐字节一致（sha256），入口文件在清单内。 */
function verifyEmbedAssets(directory) {
  const manifestPath = join(directory, 'asset-manifest.json');
  requireFile(manifestPath, '嵌入产物清单');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  if (manifest.version !== 1 || typeof manifest.entryJs !== 'string') {
    fail('嵌入产物清单格式非法（需要 version=1 与 entryJs）');
  }
  const names = Object.keys(manifest.files ?? {});
  if (names.length === 0 || !names.includes(manifest.entryJs)) {
    fail('嵌入产物清单未声明入口脚本或文件列表为空');
  }
  if (manifest.entryCss !== null && !names.includes(manifest.entryCss)) {
    fail('嵌入产物清单声明的入口样式不在文件列表内');
  }
  for (const name of names) {
    const file = join(directory, 'assets', name);
    requireFile(file, `嵌入产物 ${name}`);
    const digest = sha256(file);
    if (digest !== manifest.files[name]) {
      fail(`嵌入产物 ${name} 与清单 sha256 不一致（产物被改动或清单过期）`);
    }
  }
  return manifest;
}

function parsePnpmLicenses(directory) {
  const result = spawnSync('pnpm', ['licenses', 'list', '--json', '--prod'], {
    cwd: directory,
    encoding: 'utf8',
    env: process.env,
  });
  if (result.status !== 0) {
    fail(`pnpm licenses list 退出码 ${result.status}：${result.stderr?.trim() ?? ''}`);
  }
  return JSON.parse(result.stdout);
}

/** 从 CycloneDX 组件提取 (许可表达式, 组件) 列表；缺许可的进 UNKNOWN，不猜。 */
function backendLicenseRows(bom) {
  const rows = [];
  const unknown = [];
  for (const component of bom.components ?? []) {
    const label = `${component.group ?? ''}:${component.name}@${component.version ?? ''}`;
    const licenses = component.licenses ?? [];
    const expressions = licenses
      .map((entry) => entry.expression ?? entry.license?.id ?? entry.license?.name)
      .filter((value) => typeof value === 'string' && value.length > 0);
    if (expressions.length === 0) {
      unknown.push(label);
    } else {
      rows.push({ license: [...new Set(expressions)].sort().join(' OR '), label });
    }
  }
  return { rows, unknown };
}

function frontendLicenseRows(licenses) {
  const rows = [];
  const unknown = [];
  for (const [license, packages] of Object.entries(licenses)) {
    const isUnknown = /^unknown$/i.test(license);
    for (const entry of packages) {
      const label = `${entry.name}@${(entry.versions ?? []).join(',')}`;
      if (isUnknown || entry.license === undefined || entry.license === 'Unknown') {
        unknown.push(label);
      } else {
        rows.push({ license, label });
      }
    }
  }
  return { rows, unknown };
}

function renderNotice(delivery, backendBom, frontendLicenses) {
  const backend = backendLicenseRows(backendBom);
  const frontend = frontendLicenseRows(frontendLicenses);
  const groupByLicense = (rows) => {
    const grouped = new Map();
    for (const row of rows) {
      const list = grouped.get(row.license) ?? [];
      list.push(row.label);
      grouped.set(row.license, list);
    }
    return [...grouped.entries()].sort(([left], [right]) => left.localeCompare(right));
  };
  const lines = [];
  lines.push(`NOTICE - ${delivery.name}`);
  lines.push('');
  lines.push('本文件由 deploy/package-delivery.mjs 生成，请勿手工编辑。');
  lines.push('数据来源：');
  lines.push('  后端 = Maven 依赖的 CycloneDX 聚合 SBOM（sbom/backend-bom.json，含测试范围）');
  lines.push('  前端 = pnpm licenses list --json --prod（sbom/frontend-licenses.json，工作区视角，');
  lines.push('        包含 internal 工具包的生产依赖，是交付应用的超集；分发前仍须按实际产物复核第三方声明）');
  lines.push('缺失许可元数据的组件列在 UNKNOWN 段，代表需要人工复核，不代表无许可。');
  lines.push('');
  lines.push(`后端组件（含测试范围）：${backend.rows.length + backend.unknown.length} 个`);
  for (const [license, labels] of groupByLicense(backend.rows)) {
    lines.push(`  [${license}] ${labels.length} 个`);
    for (const label of labels.sort()) {
      lines.push(`    ${label}`);
    }
  }
  if (backend.unknown.length > 0) {
    lines.push(`  [UNKNOWN] ${backend.unknown.length} 个（需人工复核）`);
    for (const label of backend.unknown.sort()) {
      lines.push(`    ${label}`);
    }
  }
  lines.push('');
  lines.push(`前端生产依赖：${frontend.rows.length + frontend.unknown.length} 个`);
  for (const [license, labels] of groupByLicense(frontend.rows)) {
    lines.push(`  [${license}] ${labels.length} 个`);
    for (const label of labels.sort()) {
      lines.push(`    ${label}`);
    }
  }
  if (frontend.unknown.length > 0) {
    lines.push(`  [UNKNOWN] ${frontend.unknown.length} 个（需人工复核）`);
    for (const label of frontend.unknown.sort()) {
      lines.push(`    ${label}`);
    }
  }
  lines.push('');
  return { text: `${lines.join('\n')}\n`, backend, frontend };
}

function renderMigrationMarkdown(entries, snapshotVersion, baseline) {
  const lines = [];
  lines.push('# 迁移清单（脚本生成，请勿手工编辑）');
  lines.push('');
  lines.push(`- 迁移总数：${entries.length}，最新版本：V${entries.at(-1).version}`);
  lines.push(`- 快照声明版本：V${snapshotVersion}（数据库文件/basic_framework.sql 头部）`);
  lines.push(`- 已发布基线：V${baseline}（docs/ai-platform/01-framework-baseline.md）`);
  lines.push('');
  lines.push('| 版本 | 描述 | 文件 | 字节 | sha256 |');
  lines.push('|---:|---|---|---:|---|');
  for (const entry of entries) {
    lines.push(
      `| ${entry.version} | ${entry.description} | ${entry.file} | ${entry.bytes} | ${entry.sha256} |`,
    );
  }
  lines.push('');
  return lines.join('\n');
}

function copy(source, destination) {
  cpSync(source, destination, { recursive: true });
}

function listFilesRecursively(directory) {
  const files = [];
  const visit = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) {
        visit(path);
      } else if (entry.isFile()) {
        files.push(path);
      }
    }
  };
  visit(directory);
  return files.sort();
}

function writeManifest(outputDirectory, delivery, migrations, snapshotVersion, extras) {
  const files = listFilesRecursively(outputDirectory)
    .filter((file) => !file.endsWith(`${sep}MANIFEST.json`) && !file.endsWith(`${sep}SHA256SUMS`))
    .map((file) => ({
      path: relative(outputDirectory, file).replaceAll(sep, '/'),
      bytes: statSync(file).size,
      sha256: sha256(file),
    }));
  const manifest = {
    delivery: delivery.name,
    generatedAt: new Date().toISOString(),
    git: { commit: delivery.commit, branch: delivery.branch, dirty: delivery.dirty },
    migration: { count: migrations.length, latest: migrations.at(-1).version, snapshotDeclared: snapshotVersion },
    ...extras,
    files,
  };
  writeFileSync(join(outputDirectory, 'MANIFEST.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  const sums = files.map((file) => `${file.sha256}  ${file.path}`).join('\n');
  writeFileSync(
    join(outputDirectory, 'SHA256SUMS'),
    `${sums}\n${sha256(join(outputDirectory, 'MANIFEST.json'))}  MANIFEST.json\n`,
  );
  return manifest;
}

function printSummary(manifest) {
  const byCategory = new Map();
  for (const file of manifest.files) {
    const category = file.path.includes('/') ? file.path.slice(0, file.path.indexOf('/')) : '(root)';
    const entry = byCategory.get(category) ?? { count: 0, bytes: 0 };
    entry.count += 1;
    entry.bytes += file.bytes;
    byCategory.set(category, entry);
  }
  process.stdout.write('\n交付物清单：\n');
  process.stdout.write('  类别            文件数    体积\n');
  for (const [category, entry] of [...byCategory.entries()].sort()) {
    process.stdout.write(
      `  ${category.padEnd(14)}${String(entry.count).padStart(4)}  ${(entry.bytes / 1024 / 1024).toFixed(2)} MiB\n`,
    );
  }
  const total = manifest.files.reduce((sum, file) => sum + file.bytes, 0);
  process.stdout.write(`  合计            ${String(manifest.files.length).padStart(4)}  ${(total / 1024 / 1024).toFixed(2)} MiB\n`);
}

function main() {
  if (hasFlag('--help') || hasFlag('-h')) {
    process.stdout.write('用法：node deploy/package-delivery.mjs [--output=<目录>] [--no-build] [--offline] [--skip-sbom]\n');
    return;
  }
  if (!existsSync(join(BACKEND_DIR, 'mvnw'))) {
    fail(`后端目录不存在 Maven Wrapper：${BACKEND_DIR}`);
  }
  const delivery = deliveryIdentity();
  const outputDirectory = resolve(argumentValue('--output') ?? join(SCRIPT_DIR, 'dist', delivery.name));
  if (outputDirectory === REPO_ROOT || REPO_ROOT.startsWith(`${outputDirectory}${sep}`)) {
    fail(`输出目录不能是仓库根或其父目录：${outputDirectory}`);
  }

  const migrations = readMigrations();
  const snapshotVersion = snapshotDeclaredVersion();
  const latestVersion = migrations.at(-1).version;
  if (snapshotVersion !== latestVersion) {
    fail(`快照声明 V${snapshotVersion} 与迁移链最新 V${latestVersion} 不一致；先同步快照再出包`);
  }

  const build = !hasFlag('--no-build');
  if (build) {
    buildBackend(hasFlag('--offline'));
    buildFrontend();
  } else {
    log('--no-build：复用已有构建输出');
  }

  requireFile(SERVER_JAR, '可执行 jar');
  requireFile(join(ADMIN_DIST, 'index.html'), '管理端产物 index.html');
  requireFile(join(ADMIN_DIST, '_app.config.js'), '管理端产物 _app.config.js');
  requireFile(join(CHAT_DIST, 'index.html'), 'Chat 产物 index.html');
  const embedManifest = verifyEmbedAssets(EMBED_STAGED);
  const sdkFiles = readdirSync(SDK_DIST).filter((file) => /^ai-embed-sdk-.*\.js$/.test(file));
  if (sdkFiles.length !== 1) {
    fail(`SDK 产物目录应恰有一个版本化 js，实际 ${sdkFiles.length} 个：${sdkFiles.join(', ')}`);
  }
  requireFile(CONFIG_TEMPLATE, '配置模板');

  let sbomEnabled = !hasFlag('--skip-sbom');
  if (sbomEnabled) {
    buildSbom();
  } else {
    log('--skip-sbom：本次交付判定为不完整（NOTICE 仅含前端清单）');
  }

  log(`输出目录：${outputDirectory}`);
  rmSync(outputDirectory, { recursive: true, force: true });
  for (const section of ['backend', 'frontend', 'config', 'sbom', 'migrations']) {
    mkdirSync(join(outputDirectory, section), { recursive: true });
  }

  copy(SERVER_JAR, join(outputDirectory, 'backend', 'basic-framework-server.jar'));
  copy(MIGRATION_DIR, join(outputDirectory, 'backend', 'db-migration'));
  copy(ADMIN_DIST, join(outputDirectory, 'frontend', 'admin'));
  copy(CHAT_DIST, join(outputDirectory, 'frontend', 'chat'));
  copy(EMBED_STAGED, join(outputDirectory, 'frontend', 'embed-assets'));
  copy(SDK_DIST, join(outputDirectory, 'frontend', 'sdk'));
  copy(CONFIG_TEMPLATE, join(outputDirectory, 'config', 'app.env.example'));

  const backendBom = sbomEnabled ? JSON.parse(readFileSync(AGGREGATE_BOM, 'utf8')) : { components: [] };
  if (sbomEnabled) {
    copy(AGGREGATE_BOM, join(outputDirectory, 'sbom', 'backend-bom.json'));
  }
  const frontendLicenses = parsePnpmLicenses(FRONTEND_DIR);
  writeFileSync(
    join(outputDirectory, 'sbom', 'frontend-licenses.json'),
    `${JSON.stringify(frontendLicenses, null, 2)}\n`,
  );

  const notice = renderNotice(delivery, backendBom, frontendLicenses);
  writeFileSync(join(outputDirectory, 'NOTICE'), notice.text);

  writeFileSync(
    join(outputDirectory, 'migrations', 'manifest.json'),
    `${JSON.stringify(
      {
        count: migrations.length,
        latest: latestVersion,
        snapshotDeclared: snapshotVersion,
        publishedBaseline: PUBLISHED_BASELINE_VERSION,
        migrations: migrations.map(({ version, description, file, bytes: size, sha256: digest }) => ({
          version,
          description,
          file,
          bytes: size,
          sha256: digest,
        })),
      },
      null,
      2,
    )}\n`,
  );
  writeFileSync(
    join(outputDirectory, 'migrations', 'MIGRATIONS.md'),
    renderMigrationMarkdown(migrations, snapshotVersion, PUBLISHED_BASELINE_VERSION),
  );

  const versions = {
    backendRevision: delivery.revision,
    adminVersion: packageVersion(join('apps', 'web-ele', 'package.json')),
    chatVersion: packageVersion(join('apps', 'ai-chat', 'package.json')),
    embedSdkVersion: packageVersion(join('packages', 'ai-embed-sdk', 'package.json')),
    embedEntryJs: embedManifest.entryJs,
    embedEntryCss: embedManifest.entryCss,
    embedAssetCount: Object.keys(embedManifest.files).length,
    sbomIncluded: sbomEnabled,
    node: process.version,
  };
  writeFileSync(
    join(outputDirectory, 'version.json'),
    `${JSON.stringify({ delivery: delivery.name, git: { commit: delivery.commit, branch: delivery.branch, dirty: delivery.dirty }, ...versions }, null, 2)}\n`,
  );

  const manifest = writeManifest(outputDirectory, delivery, migrations, snapshotVersion, {
    versions,
    notice: {
      backendComponents: notice.backend.rows.length + notice.backend.unknown.length,
      backendUnknown: notice.backend.unknown.length,
      frontendPackages: notice.frontend.rows.length + notice.frontend.unknown.length,
      frontendUnknown: notice.frontend.unknown.length,
    },
  });
  log(`交付包完成：${outputDirectory}`);
  printSummary(manifest);
  process.stdout.write(`  NOTICE 组件：后端 ${manifest.notice.backendComponents}（UNKNOWN ${manifest.notice.backendUnknown}），前端 ${manifest.notice.frontendPackages}（UNKNOWN ${manifest.notice.frontendUnknown}）\n`);
}

main();
