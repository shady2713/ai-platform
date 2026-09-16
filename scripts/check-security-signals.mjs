import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const METRICS_SOURCE = join(
  REPO_ROOT,
  '后端代码/basic-framework-boot/basic-framework-module-system/src/main/java',
  'com/basicframework/module/system/service/metrics/SecuritySignalMetrics.java',
);
const DOC_PATH = join(REPO_ROOT, 'docs/security/security-signals.md');
const RULES_PATH = join(REPO_ROOT, 'ops/prometheus/security-signals.rules.yml');

const METRIC_PREFIX = 'basic_framework.';

/**  micrometers 计数器在 Prometheus 抓取后：点转下划线并追加 _total 后缀。 */
export function prometheusName(metricName) {
  return `${metricName.split('.').join('_')}_total`;
}

/** 从 SecuritySignalMetrics 源码提取指标名常量。 */
export function metricConstants(javaSource) {
  const names = new Set();
  const pattern = /public\s+static\s+final\s+String\s+[A-Z0-9_]+\s*=\s*"([^"]+)"/g;
  let match;
  while ((match = pattern.exec(javaSource)) !== null) {
    if (match[1].startsWith(METRIC_PREFIX)) {
      names.add(match[1]);
    }
  }
  return names;
}

/** 文本中出现的全部 basic_framework.* 指标名（用于过期登记检查）。 */
export function mentionedMetrics(text) {
  const names = new Set();
  const pattern = /basic_framework\.[a-z0-9_.]+/g;
  let match;
  while ((match = pattern.exec(text)) !== null) {
    names.add(match[0].replace(/[._]+$/, ''));
  }
  return names;
}

/** 文本中出现的全部抓取态指标名（basic_framework_ 前缀），用于检查告警规则是否过期。 */
export function mentionedPrometheusNames(text) {
  const names = new Set();
  const pattern = /basic_framework_[a-z0-9_]+/g;
  let match;
  while ((match = pattern.exec(text)) !== null) {
    names.add(match[0]);
  }
  return names;
}

/** 告警规则中声明的告警名。阈值只存在于规则文件，文档不重复数值。 */
export function alertNames(rulesText) {
  const names = new Set();
  const pattern = /-[ \t]*alert:[ \t]*([A-Za-z][A-Za-z0-9_]*)/g;
  let match;
  while ((match = pattern.exec(rulesText)) !== null) {
    names.add(match[1]);
  }
  return names;
}

/** 文档中声明的处置章节标题（### 告警名）。 */
export function runbookSections(docText) {
  const names = new Set();
  const pattern = /^###[ \t]+([A-Za-z][A-Za-z0-9_]*)[ \t]*$/gm;
  let match;
  while ((match = pattern.exec(docText)) !== null) {
    names.add(match[1]);
  }
  return names;
}

export function securitySignalFailures(metricNames, docText, rulesText) {
  const failures = [];
  for (const name of metricNames) {
    if (!docText.includes(name) && !docText.includes(prometheusName(name))) {
      failures.push(`${name}: 指标未在 docs/security/security-signals.md 中登记`);
    }
    if (!rulesText.includes(prometheusName(name))) {
      failures.push(
        `${name}: 指标未出现在 ops/prometheus/security-signals.rules.yml（期望抓取名 ${prometheusName(name)}）`,
      );
    }
  }
  for (const name of mentionedMetrics(docText)) {
    if (!metricNames.has(name)) {
      failures.push(`${name}: 文档提及的指标在 SecuritySignalMetrics 中不存在（登记已过期）`);
    }
  }
  for (const name of mentionedMetrics(rulesText)) {
    const dotted = [...metricNames].find((candidate) => prometheusName(candidate) === name);
    if (dotted === undefined) {
      failures.push(`${name}: 告警规则引用的指标在 SecuritySignalMetrics 中不存在（规则已过期）`);
    }
  }
  // 抓取态名称（点转下划线 + _total）同样不得出现未登记指标。
  const knownPrometheusNames = new Set([...metricNames].map(prometheusName));
  for (const name of mentionedPrometheusNames(docText)) {
    if (!knownPrometheusNames.has(name)) {
      failures.push(`${name}: 文档提及的抓取态指标在 SecuritySignalMetrics 中不存在（登记已过期）`);
    }
  }
  for (const name of mentionedPrometheusNames(rulesText)) {
    if (!knownPrometheusNames.has(name)) {
      failures.push(`${name}: 告警规则引用的指标在 SecuritySignalMetrics 中不存在（规则已过期）`);
    }
  }
  // 告警与处置章节必须一一对应：新增告警却没有 Runbook，等于把告警丢给无人处置的队列。
  const alerts = alertNames(rulesText);
  const sections = runbookSections(docText);
  for (const name of alerts) {
    if (!sections.has(name)) {
      failures.push(`${name}: 告警缺少 Runbook 章节（security-signals.md 需要 "### ${name}"）`);
    }
  }
  for (const name of sections) {
    if (!alerts.has(name)) {
      failures.push(`${name}: Runbook 章节没有对应的告警规则（规则已删除或改名）`);
    }
  }
  return failures;
}

function verify() {
  for (const path of [METRICS_SOURCE, DOC_PATH, RULES_PATH]) {
    if (!existsSync(path)) {
      throw new Error(`缺少安全信号文件：${path}`);
    }
  }
  const metricNames = metricConstants(readFileSync(METRICS_SOURCE, 'utf8'));
  if (metricNames.size === 0) {
    throw new Error('未能从 SecuritySignalMetrics 提取任何指标名常量');
  }
  const failures = securitySignalFailures(
    metricNames,
    readFileSync(DOC_PATH, 'utf8'),
    readFileSync(RULES_PATH, 'utf8'),
  );
  if (failures.length > 0) {
    const newline = String.fromCharCode(10);
    throw new Error(`安全信号契约失败：${newline}- ${failures.join(`${newline}- `)}`);
  }
  console.log(
    `安全信号契约通过：指标 ${metricNames.size} 个，文档与告警规则三方一致`,
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    verify();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}