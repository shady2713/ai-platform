import { describe, expect, it, vi } from 'vitest';

import { getRangePickerDefaultProps } from './index';
import { getRangePickerDefaultProps as fromSource } from './range-picker-props';

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

describe('utils index contract', () => {
  it('re-exports the range picker helper unchanged', () => {
    expect(getRangePickerDefaultProps).toBe(fromSource);
  });

  it('exposes the full range picker defaults through the barrel', () => {
    const props = getRangePickerDefaultProps();
    expect(props.format).toBe('YYYY-MM-DD HH:mm:ss');
    expect(props.valueFormat).toBe('YYYY-MM-DD HH:mm:ss');
    expect(props.startPlaceholder).toBe('utils.rangePicker.beginTime');
    expect(props.endPlaceholder).toBe('utils.rangePicker.endTime');
    expect(props.shortcuts).toHaveLength(7);
    expect(props.defaultTime).toHaveLength(2);
  });
});
