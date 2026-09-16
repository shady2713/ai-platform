import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getDictLabel, getDictObj, getDictOptions } from './use-dict';

const testState = vi.hoisted(() => ({
  dictStore: {
    getDictData: vi.fn(),
    getDictOptions: vi.fn(),
  },
}));

vi.mock('@vben/stores', () => ({
  useDictStore: () => testState.dictStore,
}));

describe('dictionary helpers', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('返回匹配字典项的标签和对象，并隔离无效结果', () => {
    const option = { label: '启用', value: '1' };
    testState.dictStore.getDictData.mockReturnValueOnce(option);

    expect(getDictLabel('common_status', 1)).toBe('启用');
    expect(testState.dictStore.getDictData).toHaveBeenCalledWith(
      'common_status',
      1,
    );

    testState.dictStore.getDictData.mockReturnValueOnce(option);
    expect(getDictObj('common_status', '1')).toBe(option);

    testState.dictStore.getDictData.mockReturnValueOnce('unexpected');
    expect(getDictLabel('common_status', true)).toBe('');

    testState.dictStore.getDictData.mockReturnValueOnce(undefined);
    expect(getDictObj('common_status', false)).toBeNull();
  });

  it('按调用方要求将字典值转换为字符串、数字或布尔值', () => {
    testState.dictStore.getDictOptions.mockReturnValue([
      { label: '启用', value: '1' },
      { label: '禁用', value: '0' },
    ]);
    expect(getDictOptions('status')).toEqual([
      { label: '启用', value: '1' },
      { label: '禁用', value: '0' },
    ]);
    expect(getDictOptions('status', 'number')).toEqual([
      { label: '启用', value: 1 },
      { label: '禁用', value: 0 },
    ]);

    testState.dictStore.getDictOptions.mockReturnValue([
      { label: '是', value: 'true' },
      { label: '否', value: 'false' },
    ]);
    expect(getDictOptions('status', 'boolean')).toEqual([
      { label: '是', value: true },
      { label: '否', value: false },
    ]);
  });

  it('遇到运行时的未知值类型或无效数字时显式失败', () => {
    testState.dictStore.getDictOptions.mockReturnValue([
      { label: '无效', value: 'not-a-number' },
    ]);
    expect(() => getDictOptions('status', 'number')).toThrow(
      '无效的数字字典值：not-a-number',
    );

    testState.dictStore.getDictOptions.mockReturnValue([
      { label: '无效', value: 'yes' },
    ]);
    expect(() => getDictOptions('status', 'boolean')).toThrow(
      '无效的布尔字典值：yes',
    );

    testState.dictStore.getDictOptions.mockReturnValue([
      { label: '启用', value: '1' },
    ]);
    expect(() => getDictOptions('status', 'unknown' as never)).toThrow(
      '不支持的字典值类型：unknown',
    );
  });

  it('无字典项时返回空数组', () => {
    testState.dictStore.getDictOptions.mockReturnValue([]);

    expect(getDictOptions('missing')).toEqual([]);
  });
});
