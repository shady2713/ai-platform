import { describe, expect, it } from 'vitest';

import {
  AI_WEBHOOK_PERMISSIONS,
  buildTargetPayload,
  canRedeliver,
  describeAttemptOutcome,
  describeDeliveryStatus,
  describeEventType,
  describeFailureCode,
  describeTargetStatus,
  isDeadLetter,
  secretRotationHint,
  shortenDigest,
  summarizeDeliveries,
  validateEventTypes,
  validateMaxAttempts,
  validateSecret,
  validateTargetCode,
  validateTargetUrl,
  WEBHOOK_EVENT_OPTIONS,
} from './data';

/** X10：Webhook 控制台的纯逻辑（权限码、白名单、地址形状、死信判定与文案）。 */
describe('ai webhook data', () => {
  it('权限码与 V88 迁移种子一致', () => {
    expect(AI_WEBHOOK_PERMISSIONS).toEqual({
      delete: 'ai:webhook:delete',
      manage: 'ai:webhook:manage',
      query: 'ai:webhook:query',
      redeliver: 'ai:webhook:redeliver',
      rotate: 'ai:webhook:rotate',
    });
  });

  it('事件白名单只含运行终态三种事件', () => {
    expect(WEBHOOK_EVENT_OPTIONS.map((item) => item.value)).toEqual([
      'RUN.SUCCEEDED',
      'RUN.FAILED',
      'RUN.CANCELLED',
    ]);
    expect(validateEventTypes(['RUN.SUCCEEDED'])).toBe('');
    expect(validateEventTypes([])).toBe('至少订阅一个事件');
    expect(validateEventTypes(['RUN.RUNNING'])).toBe('事件类型不在白名单内');
  });

  it('投递地址只接受不含凭据的 http/https 绝对地址', () => {
    expect(validateTargetUrl('https://erp.example.com/hook')).toBe('');
    expect(validateTargetUrl('http://10.0.0.8:8080/hook')).toBe('');
    expect(validateTargetUrl('')).toBe('投递地址不能为空');
    expect(validateTargetUrl('erp.example.com/hook')).toBe(
      '投递地址必须是绝对的 http/https 地址',
    );
    expect(validateTargetUrl('ftp://erp.example.com/hook')).toBe(
      '投递地址只支持 http/https',
    );
    expect(validateTargetUrl('https://user:pass@erp.example.com/hook')).toBe(
      '投递地址不能包含凭据信息（user:pass@）',
    );
    expect(
      validateTargetUrl(`https://erp.example.com/${'a'.repeat(1100)}`),
    ).toBe('投递地址超过 1024 字符上限');
  });

  it('签名密钥与尝试上限按服务端同一区间收窄', () => {
    expect(validateSecret('s3cret-signing-key-0123456789')).toBe('');
    expect(validateSecret('short')).toBe('签名密钥至少 16 位');
    expect(validateSecret('s'.repeat(129))).toBe('签名密钥最多 128 位');
    expect(validateMaxAttempts(1)).toBe('');
    expect(validateMaxAttempts(10)).toBe('');
    expect(validateMaxAttempts(11)).toBe('尝试次数必须在 1-10 之间');
    expect(validateMaxAttempts(2.5)).toBe('尝试次数必须是整数');
  });

  it('目标标识只接受小写字母数字与连字符', () => {
    expect(validateTargetCode('erp-callback')).toBe('');
    expect(validateTargetCode('Erp')).toBe(
      '目标标识只能是小写字母数字与连字符，3-64 位',
    );
    expect(validateTargetCode('ab')).toBe(
      '目标标识只能是小写字母数字与连字符，3-64 位',
    );
  });

  it('死信可人工重投，其它状态不可', () => {
    expect(isDeadLetter('FAILED')).toBe(true);
    expect(canRedeliver('FAILED')).toBe(true);
    for (const status of ['PENDING', 'RUNNING', 'SUCCEEDED', undefined]) {
      expect(canRedeliver(status)).toBe(false);
    }
  });

  it('稳定码与状态给出可读文案，未知码原样保留', () => {
    expect(describeDeliveryStatus('FAILED')).toBe('死信（可人工重投）');
    expect(describeTargetStatus('DISABLED')).toBe('停用（停发）');
    expect(describeAttemptOutcome('RETRYABLE')).toBe('可重试');
    expect(describeEventType('RUN.CANCELLED')).toBe('运行已取消');
    expect(describeFailureCode('1_003_011_006')).toBe('重试预算已耗尽（死信）');
    expect(describeFailureCode('timeout')).toBe('超时');
    expect(describeFailureCode('redirect-not-followed')).toBe(
      '重定向未跟随（需重新登记地址）',
    );
    expect(describeFailureCode('unknown-code')).toBe('unknown-code');
    expect(describeFailureCode(undefined)).toBe('—');
  });

  it('投递计数把死信单列', () => {
    const rows = [
      { status: 'SUCCEEDED' },
      { status: 'PENDING' },
      { status: 'FAILED' },
      { status: 'FAILED' },
      { status: 'RUNNING' },
    ] as never[];
    expect(summarizeDeliveries(rows)).toEqual({
      deadLetter: 2,
      delivered: 1,
      pending: 1,
      running: 1,
      total: 5,
    });
  });

  it('登记入参归一化标识与地址，密钥只随提交传递', () => {
    const payload = buildTargetPayload({
      applicationId: 7,
      code: '  ERP-Callback ',
      eventTypes: ['RUN.SUCCEEDED'],
      maxAttempts: 5,
      name: ' ERP 回调 ',
      secret: ' s3cret-signing-key-0123456789 ',
      targetUrl: ' https://erp.example.com/hook ',
    });

    expect(payload).toEqual({
      applicationId: 7,
      code: 'erp-callback',
      eventTypes: ['RUN.SUCCEEDED'],
      maxAttempts: 5,
      name: 'ERP 回调',
      secret: 's3cret-signing-key-0123456789',
      targetUrl: 'https://erp.example.com/hook',
    });
    // 未提供尝试上限时按平台默认 3 次（有界重试）
    expect(
      buildTargetPayload({ applicationId: 7, eventTypes: [] }).maxAttempts,
    ).toBe(3);
  });

  it('密钥版本提示区分未配置与已配置', () => {
    expect(secretRotationHint(0)).toContain('未配置签名密钥');
    expect(secretRotationHint(3)).toContain('版本 3');
    expect(secretRotationHint(undefined)).toContain('未配置签名密钥');
  });

  it('正文摘要只展示前缀，正文本身不出现', () => {
    expect(shortenDigest('abcdef0123456789')).toBe('abcdef012345…');
    expect(shortenDigest('short')).toBe('short');
    expect(shortenDigest(undefined)).toBe('—');
  });
});
