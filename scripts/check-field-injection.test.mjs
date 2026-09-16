import assert from 'node:assert/strict';
import test from 'node:test';
import {
  baselineFailures,
  countDirectValueFieldInjections,
  countFieldInjectionAnnotations,
  fileRatchetFailures,
  ratchetFailures,
} from './check-field-injection.mjs';

test('只统计生产代码中的注入注解文本', () => {
  const source = `
    @Resource private First first;
    // @Autowired private Ignored line;
    /* @Inject private Ignored block; */
    String value = "@Resource";
    char marker = '@';
    @Inject Second second;
  `;

  assert.equal(countFieldInjectionAnnotations(source), 2);
});

test('只统计字段上的 Value 注入，不把构造器参数或工厂方法算入字段注入', () => {
  const source = `
    @Value("${'${demo.name}'}")
    @Getter
    private String name;

    @Value("${'${demo.timeout}'}") protected final Duration timeout = Duration.ZERO;

    Configuration(@Value("${'${demo.name}'}") String applicationName) {}

    @Bean
    Client client(@Value("${'${demo.url}'}") String url) {
      return new Client(url);
    }
  `;

  assert.equal(countDirectValueFieldInjections(source), 2);
  assert.equal(countFieldInjectionAnnotations(source), 2);
});

test('当前数量不得超过机械基线', () => {
  assert.deepEqual(ratchetFailures(209, 209), []);
  assert.deepEqual(ratchetFailures(208, 209), []);
  assert.match(ratchetFailures(210, 209)[0], /增加到 210/);
});

test('基线上限只允许降低', () => {
  assert.deepEqual(baselineFailures(208, 209), []);
  assert.match(baselineFailures(210, 209)[0], /上调为 210/);
});

test('任何文件都不得用其他文件的减少抵消新增注入', () => {
  const failures = fileRatchetFailures(
    { 'Existing.java': 2, 'New.java': 1, 'Reduced.java': 1 },
    { 'Existing.java': 1, 'Reduced.java': 2 },
  );

  assert.equal(failures.length, 2);
  assert.match(failures[0], /Existing.java.*从 1 增加到 2/);
  assert.match(failures[1], /New.java.*从 0 增加到 1/);
});
