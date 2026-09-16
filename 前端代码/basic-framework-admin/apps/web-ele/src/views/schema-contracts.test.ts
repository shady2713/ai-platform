import { describe, expect, it, vi } from 'vitest';

import * as infraConfig from './infra/config/data';
import * as infraFile from './infra/file/data';
import * as infraJobLog from './infra/job/logger/data';
import * as loginLog from './system/login-log/data';
import * as notice from './system/notice/data';
import * as notifyMessage from './system/notify/message/data';
import * as notifyTemplate from './system/notify/template/data';
import * as operateLog from './system/operate-log/data';
import * as post from './system/post/data';
import * as session from './system/session/data';
import * as smsTemplate from './system/sms/template/data';

vi.mock('@vben/constants', () => ({
  CommonStatusEnum: { ENABLE: 0 },
  DICT_TYPE: new Proxy(
    {},
    { get: (_target, property) => String(property).toLowerCase() },
  ),
}));

vi.mock('@vben/hooks', () => ({ getDictOptions: vi.fn(() => []) }));

vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => `formatted:${String(value)}`,
  handleTree: (value: unknown) => value,
}));

vi.mock('#/adapter/form', async () => {
  const { z } = await import('@vben/common-ui');
  return {
    buildOptionalEmailSchema: () => 'optionalEmail',
    buildOptionalMobileSchema: () => 'optionalMobile',
    buildOptionalRemarkSchema: () => 'optionalRemark',
    buildRequiredNicknameSchema: () => 'requiredNickname',
    buildRequiredPasswordSchema: () => 'requiredPassword',
    buildRequiredUsernameSchema: () => 'requiredUsername',
    z,
  };
});

vi.mock('#/api/system/user', () => ({
  getSimpleUserList: vi.fn(async () => []),
}));

vi.mock('#/components/dict-tag', () => ({ DictTag: { name: 'DictTag' } }));

vi.mock('#/utils', () => ({
  getRangePickerDefaultProps: () => ({ format: 'YYYY-MM-DD HH:mm:ss' }),
}));

interface SchemaItem {
  field?: string;
  fieldName?: string;
  render?: (value: unknown, record?: unknown) => unknown;
  slots?: { default?: string };
}

function fieldNames(items: SchemaItem[]): Array<string | undefined> {
  return items.map((item) => item.fieldName ?? item.field);
}

function expectUniqueFields(items: SchemaItem[]): void {
  const names = fieldNames(items).filter(
    (fieldName): fieldName is string => fieldName !== undefined,
  );
  expect(new Set(names).size).toBe(names.length);
}

function expectActionColumn(items: SchemaItem[] | undefined): void {
  expect(items?.some((item) => item.slots?.default === 'actions')).toBe(true);
}

describe('management view schema contracts', () => {
  it('keeps create and edit forms free of duplicate backend field names', () => {
    const forms = [
      infraConfig.useFormSchema(),
      infraFile.useFormSchema(),
      notice.useFormSchema(),
      notifyTemplate.useFormSchema(),
      post.useFormSchema(),
      smsTemplate.useFormSchema(),
    ] as SchemaItem[][];

    for (const form of forms) {
      expectUniqueFields(form);
    }
    expect(fieldNames(infraConfig.useFormSchema() as SchemaItem[])).toEqual(
      expect.arrayContaining(['category', 'key', 'value', 'visible']),
    );
    expect(fieldNames(notifyTemplate.useFormSchema() as SchemaItem[])).toEqual(
      expect.arrayContaining(['code', 'content', 'nickname', 'status']),
    );
    expect(fieldNames(smsTemplate.useFormSchema() as SchemaItem[])).toEqual(
      expect.arrayContaining(['channelId', 'apiTemplateId', 'content']),
    );
  });

  it('keeps search and table schemas aligned with action columns', () => {
    const searches = [
      infraConfig.useGridFormSchema(),
      infraFile.useGridFormSchema(),
      infraJobLog.useGridFormSchema(),
      loginLog.useGridFormSchema(),
      notice.useGridFormSchema(),
      notifyMessage.useGridFormSchema(),
      notifyTemplate.useGridFormSchema(),
      operateLog.useGridFormSchema(),
      post.useGridFormSchema(),
      session.useGridFormSchema(),
      smsTemplate.useGridFormSchema(),
    ] as SchemaItem[][];

    for (const search of searches) {
      expectUniqueFields(search);
    }

    const actionGrids = [
      infraConfig.useGridColumns(),
      infraFile.useGridColumns(),
      notice.useGridColumns(),
      notifyTemplate.useGridColumns(),
      post.useGridColumns(),
      smsTemplate.useGridColumns(),
    ] as SchemaItem[][];
    for (const grid of actionGrids) {
      expectActionColumn(grid);
    }

    expect(fieldNames(session.useGridColumns() as SchemaItem[])).toEqual(
      expect.arrayContaining(['userId', 'userType', 'accessExpiresTime']),
    );
    expect(fieldNames(operateLog.useGridColumns() as SchemaItem[])).toEqual(
      expect.arrayContaining(['type', 'subType', 'action', 'createTime']),
    );
  });

  it('renders audit and notification detail edge cases safely', () => {
    const render = (
      details: SchemaItem[],
      field: string,
      value: unknown,
      record?: unknown,
    ) => details.find((item) => item.field === field)?.render?.(value, record);

    const notifyDetails = notifyMessage.useDetailSchema() as SchemaItem[];
    expect(render(notifyDetails, 'readTime', null)).toBe('formatted:null');
    expect(render(notifyDetails, 'readTime', '2026-01-01')).toBe(
      'formatted:2026-01-01',
    );

    const jobDetails = infraJobLog.useDetailSchema() as SchemaItem[];
    expect(render(jobDetails, 'duration', 150)).toBe('150 毫秒');
    expect(render(jobDetails, 'duration', 0)).toBe('');
    expect(render(jobDetails, 'beginTime', 'start', { endTime: 'end' })).toBe(
      'formatted:start ~ formatted:end',
    );
    expect(render(jobDetails, 'beginTime', 'start')).toBe('');

    expect(fieldNames(loginLog.useDetailSchema() as SchemaItem[])).toEqual(
      expect.arrayContaining(['username', 'result', 'userIp', 'createTime']),
    );
    expect(fieldNames(operateLog.useDetailSchema() as SchemaItem[])).toEqual(
      expect.arrayContaining([
        'traceId',
        'action',
        'extra',
        'requestUrl',
        'createTime',
      ]),
    );
  });
});
