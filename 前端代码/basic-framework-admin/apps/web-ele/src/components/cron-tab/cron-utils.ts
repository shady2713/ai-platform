import type { CronItem, CronValue } from './types';

import { createDefaultCronValue } from './types';

type CronField = keyof CronValue;
type AnyCronItem = CronValue[CronField];
type CronSegments = [
  second: string,
  minute: string,
  hour: string,
  day: string,
  month: string,
  week: string,
  year?: string,
];

const NUMERIC_FIELD_BOUNDS = {
  day: { max: 31, min: 1 },
  hour: { max: 23, min: 0 },
  minute: { max: 59, min: 0 },
  month: { max: 12, min: 1 },
  second: { max: 59, min: 0 },
  year: { max: 2199, min: 1970 },
} as const;

export function formatCronField(field: CronField, item: AnyCronItem): string {
  switch (item.type) {
    case '-1': {
      return '';
    }
    case '0': {
      return '*';
    }
    case '1': {
      return `${item.range.start}-${item.range.end}`;
    }
    case '2': {
      return field === 'week'
        ? `${item.loop.end}#${item.loop.start}`
        : `${item.loop.start}/${item.loop.end}`;
    }
    case '3': {
      const fallback = field === 'year' ? '' : '*';
      return item.appoint.length > 0 ? item.appoint.join(',') : fallback;
    }
    case '4': {
      return field === 'week' ? `${item.last}L` : 'L';
    }
    case '5': {
      return '?';
    }
    default: {
      throw new Error(`Unsupported Cron field type: ${item.type}`);
    }
  }
}

export function formatCronExpression(value: CronValue): string {
  const fields = (
    ['second', 'minute', 'hour', 'day', 'month', 'week'] as const
  ).map((field) => formatCronField(field, value[field]));
  const year = formatCronField('year', value.year);
  return year ? `${fields.join(' ')} ${year}` : fields.join(' ');
}

export function parseCronExpression(expression: string): CronValue | undefined {
  const fields = expression.trim().split(/\s+/);
  if (fields.length < 6 || fields.length > 7) {
    return undefined;
  }
  const [second, minute, hour, day, month, week, year] = fields as CronSegments;

  const value = createDefaultCronValue();
  const parsed =
    parseNumericField(second, value.second, NUMERIC_FIELD_BOUNDS.second) &&
    parseNumericField(minute, value.minute, NUMERIC_FIELD_BOUNDS.minute) &&
    parseNumericField(hour, value.hour, NUMERIC_FIELD_BOUNDS.hour) &&
    parseDayField(day, value.day) &&
    parseNumericField(month, value.month, NUMERIC_FIELD_BOUNDS.month) &&
    parseWeekField(week, value.week) &&
    parseYearField(year, value.year);
  const hasExactlyOneUnspecifiedDay =
    (value.day.type === '5') !== (value.week.type === '5');
  if (!parsed || !hasExactlyOneUnspecifiedDay) {
    return undefined;
  }
  return value;
}

interface NumericBounds {
  max: number;
  min: number;
}

function parseBoundedInteger(value: string, bounds: NumericBounds) {
  if (!/^\d+$/.test(value)) return undefined;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) &&
    parsed >= bounds.min &&
    parsed <= bounds.max
    ? parsed
    : undefined;
}

function parseNumericField(
  segment: string,
  item: CronItem,
  bounds: NumericBounds,
): boolean {
  if (segment === '*') {
    item.type = '0';
    return true;
  }
  const wildcardLoop = /^\*\/(\d+)$/.exec(segment);
  if (wildcardLoop) {
    const interval = parseBoundedInteger(wildcardLoop[1] ?? '', {
      max: bounds.max - bounds.min + 1,
      min: 1,
    });
    if (interval === undefined) return false;
    item.type = '2';
    item.loop.start = bounds.min;
    item.loop.end = interval;
    return true;
  }
  const range = /^(\d+)-(\d+)$/.exec(segment);
  if (range) {
    const start = parseBoundedInteger(range[1] ?? '', bounds);
    const end = parseBoundedInteger(range[2] ?? '', bounds);
    if (start === undefined || end === undefined || start > end) return false;
    item.type = '1';
    item.range.start = start;
    item.range.end = end;
    return true;
  }
  const loop = /^(\d+)\/(\d+)$/.exec(segment);
  if (loop) {
    const start = parseBoundedInteger(loop[1] ?? '', bounds);
    const interval = parseBoundedInteger(loop[2] ?? '', {
      max: bounds.max - bounds.min + 1,
      min: 1,
    });
    if (start === undefined || interval === undefined) return false;
    item.type = '2';
    item.loop.start = start;
    item.loop.end = interval;
    return true;
  }
  const appointed = segment
    .split(',')
    .map((value) => parseBoundedInteger(value, bounds));
  if (appointed.includes(undefined)) return false;
  item.type = '3';
  item.appoint = appointed.map(String);
  return true;
}

function parseDayField(segment: string, item: CronItem): boolean {
  if (segment === '?') {
    item.type = '5';
    return true;
  } else if (segment === 'L') {
    item.type = '4';
    return true;
  }
  return parseNumericField(segment, item, NUMERIC_FIELD_BOUNDS.day);
}

function parseWeekField(segment: string, item: CronValue['week']): boolean {
  if (segment === '?') {
    item.type = '5';
    return true;
  }
  const ordinal = /^(\d+)#(\d+)$/.exec(segment);
  if (ordinal) {
    const weekday = parseBoundedInteger(ordinal[1] ?? '', { max: 7, min: 1 });
    const occurrence = parseBoundedInteger(ordinal[2] ?? '', {
      max: 5,
      min: 1,
    });
    if (weekday === undefined || occurrence === undefined) return false;
    item.type = '2';
    item.loop.start = occurrence;
    item.loop.end = String(weekday);
    return true;
  }
  const last = /^(\d+)L$/.exec(segment);
  if (last) {
    const lastValue = parseBoundedInteger(last[1] ?? '', { max: 7, min: 1 });
    if (lastValue === undefined) return false;
    item.type = '4';
    item.last = String(lastValue);
    return true;
  }
  const range = /^(\d+)-(\d+)$/.exec(segment);
  if (range) {
    const start = parseBoundedInteger(range[1] ?? '', { max: 7, min: 1 });
    const end = parseBoundedInteger(range[2] ?? '', { max: 7, min: 1 });
    if (start === undefined || end === undefined || start > end) return false;
    item.type = '1';
    item.range.start = String(start);
    item.range.end = String(end);
    return true;
  }
  if (segment === '*') {
    item.type = '0';
    return true;
  }
  const appointed = segment
    .split(',')
    .map((value) => parseBoundedInteger(value, { max: 7, min: 1 }));
  if (appointed.includes(undefined)) return false;
  item.type = '3';
  item.appoint = appointed.map(String);
  return true;
}

function parseYearField(segment: string | undefined, item: CronItem): boolean {
  if (segment === undefined) {
    item.type = '-1';
    return true;
  }
  return parseNumericField(segment, item, NUMERIC_FIELD_BOUNDS.year);
}
