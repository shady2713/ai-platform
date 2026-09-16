import assert from 'node:assert/strict';
import test from 'node:test';

import {
  alertNames,
  mentionedMetrics,
  mentionedPrometheusNames,
  metricConstants,
  prometheusName,
  runbookSections,
  securitySignalFailures,
} from './check-security-signals.mjs';

const JAVA = [
  'public class SecuritySignalMetrics {',
  '    public static final String LOGIN_FAILURES = "basic_framework.auth.login.failures";',
  '    public static final String SESSION_REVOCATIONS = "basic_framework.auth.session.revocations";',
  '    private static final String IGNORED = "not_a_metric";',
  '}',
].join(String.fromCharCode(10));

test('计数器名转换为 Prometheus 抓取名', () => {
  assert.equal(
    prometheusName('basic_framework.auth.login.failures'),
    'basic_framework_auth_login_failures_total',
  );
});

test('只提取指标前缀常量，忽略其他常量', () => {
  const names = [...metricConstants(JAVA)].sort();
  assert.deepEqual(names, [
    'basic_framework.auth.login.failures',
    'basic_framework.auth.session.revocations',
  ]);
});

test('文档提及的指标可提取，且点号与下划线两种形态互不混淆', () => {
  const sample = '见 basic_framework.auth.login.failures 与 basic_framework_auth_x_total';
  assert.deepEqual([...mentionedMetrics(sample)].sort(), [
    'basic_framework.auth.login.failures',
  ]);
  assert.deepEqual([...mentionedPrometheusNames(sample)].sort(), [
    'basic_framework_auth_x_total',
  ]);
});

test('指标未登记文档时被拒绝', () => {
  const failures = securitySignalFailures(new Set(['basic_framework.auth.login.failures']), '', '');
  assert.equal(failures.length, 2);
  assert.ok(failures.some((item) => item.includes('security-signals.md')));
  assert.ok(failures.some((item) => item.includes('security-signals.rules.yml')));
});

test('告警规则缺失抓取名时被拒绝', () => {
  const failures = securitySignalFailures(
    new Set(['basic_framework.auth.login.failures']),
    'basic_framework.auth.login.failures',
    'no rules here',
  );
  assert.equal(failures.length, 1);
  assert.ok(failures[0].includes('basic_framework_auth_login_failures_total'));
});

test('文档登记过期时被拒绝', () => {
  const failures = securitySignalFailures(
    new Set(['basic_framework.auth.login.failures']),
    'basic_framework.auth.login.failures 与 basic_framework.auth.removed.signal',
    'basic_framework_auth_login_failures_total',
  );
  assert.equal(failures.length, 1);
  assert.ok(failures[0].includes('登记已过期'));
});

test('告警规则引用不存在的指标时被拒绝', () => {
  const failures = securitySignalFailures(
    new Set(['basic_framework.auth.login.failures']),
    'basic_framework.auth.login.failures',
    'basic_framework_auth_login_failures_total basic_framework_auth_ghost_total',
  );
  assert.equal(failures.length, 1);
  assert.ok(failures[0].includes('规则已过期'));
});

test('三方一致时通过', () => {
  const failures = securitySignalFailures(
    new Set(['basic_framework.auth.login.failures']),
    'basic_framework.auth.login.failures',
    'basic_framework_auth_login_failures_total',
  );
  assert.deepEqual(failures, []);
});
test('告警名与处置章节名可被提取', () => {
  const rules = 'rules:\n  - alert: FooAlert\n    expr: x > 1\n  - alert: BarAlert\n';
  assert.deepEqual([...alertNames(rules)].sort(), ['BarAlert', 'FooAlert']);
  const doc = '## 处置\n\n### FooAlert\n\n步骤\n\n### BarAlert\n';
  assert.deepEqual([...runbookSections(doc)].sort(), ['BarAlert', 'FooAlert']);
});

test('告警缺少 Runbook 章节时被拒绝', () => {
  const failures = securitySignalFailures(
    new Set(['basic_framework.auth.login.failures']),
    'basic_framework.auth.login.failures',
    'basic_framework_auth_login_failures_total\n  - alert: GhostAlert\n',
  );
  assert.equal(failures.length, 1);
  assert.match(failures[0], /GhostAlert/);
  assert.match(failures[0], /缺少 Runbook 章节/);
});

test('Runbook 章节没有对应告警时被拒绝', () => {
  const failures = securitySignalFailures(
    new Set(['basic_framework.auth.login.failures']),
    'basic_framework.auth.login.failures\n### StaleSection\n',
    'basic_framework_auth_login_failures_total',
  );
  assert.equal(failures.length, 1);
  assert.match(failures[0], /StaleSection/);
  assert.match(failures[0], /没有对应的告警规则/);
});
