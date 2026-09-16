import assert from 'node:assert/strict';
import test from 'node:test';
import {
  countLines,
  isSvgArtworkVue,
  sourceSizeFailures,
  technicalDebtMarkerFailures,
} from './check-source-quality.mjs';

test('跨平台统计源码行数且不把末尾换行算作空行', () => {
  assert.equal(countLines(''), 0);
  assert.equal(countLines('first'), 1);
  assert.equal(countLines('first\nsecond\n'), 2);
  assert.equal(countLines('first\r\nsecond'), 2);
});

test('超过 800 行的源码失败，800 行边界通过', () => {
  const failures = sourceSizeFailures([
    { path: 'scripts/at-limit.mjs', content: Array(800).fill('line').join('\n') },
    { path: 'scripts/too-large.mjs', content: Array(801).fill('line').join('\n') },
  ]);

  assert.deepEqual(failures, ['scripts/too-large.mjs: 801 行，超过上限 800 行']);
});

test('无脚本的 SVG Vue 图稿按静态资产处理', () => {
  const artwork = '<template>\n  <svg viewBox="0 0 10 10">\n  </svg>\n</template>\n';

  assert.equal(isSvgArtworkVue('icons/logo.vue', artwork), true);
  assert.equal(isSvgArtworkVue('icons/logo.vue', `${artwork}<script setup></script>`), false);
  assert.deepEqual(
    sourceSizeFailures([
      { path: 'icons/logo.vue', content: Array(900).fill(artwork).join('\n') },
    ]),
    [],
  );
});

test('技术债标记必须在同一行携带负责人或问题引用', () => {
  const failures = technicalDebtMarkerFailures([
    {
      path: 'Example.java',
      content: [
        '// TODO 补充边界测试',
        '// FIXME(owner: shady) 已明确负责人',
        '// XXX SEC-42 已登记问题',
        'String example = "XXX/YYY";',
      ].join('\n'),
    },
  ]);

  assert.deepEqual(failures, ['Example.java:1: TODO 缺少负责人或问题引用']);
});

test('支持中文负责人、用户引用、数字 issue 与链接', () => {
  const failures = technicalDebtMarkerFailures([
    {
      path: 'valid.ts',
      content: [
        '// TODO 负责人：张三',
        '// FIXME @shady',
        '# XXX #123',
        '<!-- TODO https://issues.example.test/456 -->',
      ].join('\n'),
    },
  ]);

  assert.deepEqual(failures, []);
});
