import { createApp } from 'vue';

import { defineStore, setActivePinia } from 'pinia';
import { describe, expect, it, vi } from 'vitest';

import { initStores, resetAllStores } from './setup';

describe('store setup', () => {
  it('通过公开 Pinia 插件接口登记并重置所有 store', async () => {
    const app = createApp({});
    const pinia = await initStores(app, { namespace: 'test' });
    setActivePinia(pinia);
    const useCounterStore = defineStore('reset-test', {
      state: () => ({ count: 0 }),
    });
    const store = useCounterStore();
    store.count = 2;

    resetAllStores();

    expect(store.count).toBe(0);

    const reset = vi.spyOn(store, '$reset');
    store.$dispose();
    resetAllStores();
    expect(reset).not.toHaveBeenCalled();
  });
});
