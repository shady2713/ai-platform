import type { CronFieldType } from './types';

import { describe, expect, it } from 'vitest';

import {
  formatCronExpression,
  formatCronField,
  parseCronExpression,
} from './cron-utils';
import { createDefaultCronValue } from './types';

describe('cron 表达式转换', () => {
  it('为每个组件实例创建独立状态', () => {
    const first = createDefaultCronValue();
    const second = createDefaultCronValue();

    first.second.type = '3';
    first.second.appoint.push('10');

    expect(second.second.type).toBe('0');
    expect(second.second.appoint).toEqual([]);
  });

  it.each([
    '0 15 3 ? * 2',
    '0 0 0 ? * 2#3',
    '0 0 0 ? * 2L',
    '0 0 0 ? * 2-6',
    '0 0 0 1 1 ? 2026/2',
    '0 0 0 L * ? 2026-2030',
  ])('无损往返受支持的表达式：%s', (expression) => {
    const parsed = parseCronExpression(expression);

    expect(parsed).toBeDefined();
    if (!parsed) {
      throw new Error(`表达式解析失败：${expression}`);
    }
    expect(formatCronExpression(parsed)).toBe(expression);
  });

  it('按开始年份和间隔解析年份字段', () => {
    const parsed = parseCronExpression('0 0 0 1 1 ? 2026/2');

    expect(parsed?.year.type).toBe('2');
    expect(parsed?.year.loop).toEqual({ start: 2026, end: 2 });
  });

  it('将 Quartz 通配符间隔规范化为字段最小值起点', () => {
    const parsed = parseCronExpression('*/5 * * * * ?');

    expect(parsed).toBeDefined();
    if (!parsed) throw new Error('通配符间隔解析失败');
    expect(formatCronExpression(parsed)).toBe('0/5 * * * * ?');
  });

  it.each([
    '',
    '* * * * *',
    '* * * * * ? * extra',
    'invalid * * * * ?',
    '* * * * * 2#x',
  ])('拒绝不完整或不受支持的表达式：%s', (expression) => {
    expect(parseCronExpression(expression)).toBeUndefined();
  });

  it('接受每个字段的闭区间边界', () => {
    expect(parseCronExpression('59 59 23 31 12 ? 2199')).toBeDefined();
    expect(parseCronExpression('0 0 0 ? 1 7 1970')).toBeDefined();
  });

  it.each([
    '60 0 0 * * ?',
    '0 60 0 * * ?',
    '0 0 24 * * ?',
    '0 0 0 0 * ?',
    '0 0 0 32 * ?',
    '0 0 0 * 0 ?',
    '0 0 0 * 13 ?',
    '0 0 0 ? * 0',
    '0 0 0 ? * 8',
    '0 0 0 * * ? 1969',
    '0 0 0 * * ? 2200',
    '10-5 0 0 * * ?',
    '0/0 0 0 * * ?',
    '0/61 0 0 * * ?',
    '*/0 0 0 * * ?',
    '*/61 0 0 * * ?',
    '0,60 0 0 * * ?',
    '0 0 0 ? * 2#0',
    '0 0 0 ? * 2#6',
    '0 0 0 ? * 8#1',
    '0 0 0 ? * 8L',
    '0 0 0 ? * 7-2',
    '0 0 0 * * *',
    '0 0 0 ? * ?',
  ])('拒绝越界、倒序或日周冲突的表达式：%s', (expression) => {
    expect(parseCronExpression(expression)).toBeUndefined();
  });

  it('拒绝未知字段类型而不是静默生成表达式', () => {
    const value = createDefaultCronValue();
    value.second.type = 'unknown' as CronFieldType;

    expect(() => formatCronExpression(value)).toThrow(
      'Unsupported Cron field type: unknown',
    );
  });

  it('为空的指定年份生成空字段，为空的普通指定字段生成通配符', () => {
    const value = createDefaultCronValue();
    value.second.type = '3';
    value.year.type = '3';

    expect(formatCronField('second', value.second)).toBe('*');
    expect(formatCronField('year', value.year)).toBe('');
  });
});
