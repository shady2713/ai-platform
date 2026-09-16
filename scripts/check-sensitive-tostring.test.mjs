import assert from 'node:assert/strict';
import test from 'node:test';

import {
  classToStringExclusions,
  isSensitiveToStringField,
  sensitiveToStringFailures,
} from './check-sensitive-tostring.mjs';

test('拒绝 Lombok Data 对象中未排除的凭据字段', () => {
  const source = ['@Data', 'class Sample {', '    private String accessToken;', '}'].join('\n');

  assert.deepEqual(sensitiveToStringFailures('module/Sample.java', source), [
    { fieldName: 'accessToken', line: 3 },
  ]);
});

test('字段级 ToString 排除可保护密码', () => {
  const source = [
    '@Data',
    'class Sample {',
    '    @ToString.Exclude',
    '    private String password;',
    '}',
  ].join('\n');

  assert.deepEqual(sensitiveToStringFailures('module/Sample.java', source), []);
});

test('拒绝 Lombok Data 对象中未排除的直接联系方式', () => {
  const source = ['@Data', 'class Sample {', '    private String email;', '}'].join('\n');

  assert.deepEqual(sensitiveToStringFailures('module/Sample.java', source), [
    { fieldName: 'email', line: 3 },
  ]);
});

test('兼容类级 ToString exclude 声明', () => {
  const source = [
    '@Data',
    '@ToString(callSuper = true, exclude = {"mobile", "code"})',
    'class Sample {',
    '    private String mobile;',
    '    private String code;',
    '}',
  ].join('\n');

  assert.deepEqual([...classToStringExclusions(source)], ['mobile', 'code']);
  assert.deepEqual(
    sensitiveToStringFailures('controller/admin/auth/vo/Sample.java', source),
    [],
  );
});

test('验证码只在认证与短信边界强制排除，直接联系方式全局排除', () => {
  assert.equal(isSensitiveToStringField('module/Sample.java', 'code'), false);
  assert.equal(isSensitiveToStringField('/service/auth/dto/Sample.java', 'code'), true);
  assert.equal(isSensitiveToStringField('module/Sample.java', 'email'), true);
  assert.equal(isSensitiveToStringField('module/Sample.java', 'mobile'), true);
  assert.equal(isSensitiveToStringField('module/Sample.java', 'phone'), true);
  assert.equal(isSensitiveToStringField('module/Sample.java', 'tokenHeader'), false);
});
