import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useDictStore } from './dict';

describe('useDictStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('按字符串语义读取字典值', () => {
    const store = useDictStore();
    store.setDictCache({
      status: [
        { label: '启用', value: '1' },
        { label: '停用', value: '0' },
      ],
    });

    expect(store.getDictData('status', 1)?.label).toBe('启用');
    expect(store.getDictData('status', '0')?.label).toBe('停用');
    expect(store.getDictData('missing', 1)).toBeUndefined();
    expect(store.getDictOptions('missing')).toEqual([]);
  });

  it('将接口字段稳定映射为字符串字典项', async () => {
    const api = vi.fn(async (params: Record<string, unknown>) => [
      {
        code: 7,
        dictType: String(params.type),
        name: '七号',
      },
    ]);
    const store = useDictStore();

    await store.setDictCacheByApi(api, { type: 'number' }, 'name', 'code');

    expect(api).toHaveBeenCalledWith({ type: 'number' });
    expect(store.getDictOptions('number')).toEqual([
      {
        colorType: undefined,
        cssClass: undefined,
        label: '七号',
        value: '7',
      },
    ]);
  });
});
