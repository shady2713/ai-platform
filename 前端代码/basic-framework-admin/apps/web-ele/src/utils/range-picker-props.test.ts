import { describe, expect, it, vi } from 'vitest';

import { getRangePickerDefaultProps } from './range-picker-props';

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

describe('getRangePickerDefaultProps', () => {
  it('provides valid labels, formats and date ranges for every shortcut', () => {
    const props = getRangePickerDefaultProps();

    expect(props.format).toBe('YYYY-MM-DD HH:mm:ss');
    expect(props.valueFormat).toBe('YYYY-MM-DD HH:mm:ss');
    expect(props.startPlaceholder).toBe('utils.rangePicker.beginTime');
    expect(props.endPlaceholder).toBe('utils.rangePicker.endTime');
    expect(props.defaultTime).toHaveLength(2);
    expect(props.shortcuts).toHaveLength(7);

    for (const shortcut of props.shortcuts) {
      const range = shortcut.value();
      const [start, end] = range;
      if (start === undefined || end === undefined) {
        throw new Error('Range shortcut must return a start and end date');
      }
      expect(shortcut.text).toMatch(/^utils\.rangePicker\./);
      expect(range).toHaveLength(2);
      expect(start.isValid()).toBe(true);
      expect(end.isValid()).toBe(true);
      expect(start.isAfter(end)).toBe(false);
    }
  });
});
