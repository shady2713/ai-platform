import dayjs from 'dayjs';
import timezone from 'dayjs/plugin/timezone';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);
dayjs.extend(timezone);

type FormatDate = Date | dayjs.Dayjs | null | number | string;

type Format =
  | 'HH'
  | 'HH:mm'
  | 'HH:mm:ss'
  | 'YYYY'
  | 'YYYY-MM'
  | 'YYYY-MM-DD'
  | 'YYYY-MM-DD HH'
  | 'YYYY-MM-DD HH:mm'
  | 'YYYY-MM-DD HH:mm:ss'
  | (string & {});

export function formatDate(time?: FormatDate, format: Format = 'YYYY-MM-DD') {
  // 日期不存在，则返回空
  if (time === null || time === undefined || time === '') {
    return '';
  }
  try {
    const date = dayjs.isDayjs(time) ? time : dayjs(time);
    if (!date.isValid()) {
      throw new Error('Invalid date');
    }
    return date.tz().format(format);
  } catch {
    console.warn('[date] Unable to format an invalid date value');
    return String(time ?? '');
  }
}

export function formatDateTime(time?: FormatDate) {
  return formatDate(time, 'YYYY-MM-DD HH:mm:ss');
}

export function formatDate2(
  date: Date | null | undefined,
  format?: string,
): string {
  if (!date) {
    return '';
  }
  return dayjs(date).format(format ?? 'YYYY-MM-DD HH:mm:ss');
}

export function isDate(value: unknown): value is Date {
  return value instanceof Date;
}

export function isDayjsObject(value: unknown): value is dayjs.Dayjs {
  return dayjs.isDayjs(value);
}

function isFormatDate(value: unknown): value is FormatDate {
  return (
    typeof value === 'number' ||
    typeof value === 'string' ||
    isDate(value) ||
    isDayjsObject(value)
  );
}

/**
 * element plus 的时间 Formatter 实现，使用 YYYY-MM-DD HH:mm:ss 格式
 *
 * @param _row
 * @param _column
 * @param cellValue 字段值
 */
export function dateFormatter(
  _row: unknown,
  _column: unknown,
  cellValue: unknown,
): string {
  return isFormatDate(cellValue) ? formatDate(cellValue) : '';
}

/**
 * 获取当前时区
 * @returns 当前时区
 */
export const getSystemTimezone = () => {
  return dayjs.tz.guess();
};

/**
 * 自定义设置的时区
 */
let currentTimezone = getSystemTimezone();

/**
 * 设置默认时区
 * @param timezone
 */
export const setCurrentTimezone = (timezone?: string) => {
  currentTimezone = timezone || getSystemTimezone();
  dayjs.tz.setDefault(currentTimezone);
};

/**
 * 获取设置的时区
 * @returns 设置的时区
 */
export const getCurrentTimezone = () => {
  return currentTimezone;
};
