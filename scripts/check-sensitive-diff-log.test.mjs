import assert from 'node:assert/strict';
import test from 'node:test';

import { sensitiveDiffLogFailures } from './check-sensitive-diff-log.mjs';

test('拒绝操作审计中未脱敏的邮箱', () => {
  const source = [
    '@DiffLogField(name = "用户邮箱")',
    'private String email;',
  ].join('\n');

  assert.deepEqual(sensitiveDiffLogFailures(source), [
    {
      fieldName: 'email',
      expectedFunction: 'EmailDesensitizeParseFunction.NAME',
      line: 2,
    },
  ]);
});

test('允许操作审计中绑定邮箱和手机号脱敏函数', () => {
  const source = [
    '@DiffLogField(name = "用户邮箱", function = EmailDesensitizeParseFunction.NAME)',
    'private String email;',
    '@DiffLogField(name = "手机号", function = MobileDesensitizeParseFunction.NAME)',
    'private String mobile;',
  ].join('\n');

  assert.deepEqual(sensitiveDiffLogFailures(source), []);
});

test('未审计的联系方式字段不要求操作日志函数', () => {
  const source = ['private String mobile;'].join('\n');

  assert.deepEqual(sensitiveDiffLogFailures(source), []);
});
