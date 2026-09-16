import type { Pinia, StoreGeneric } from 'pinia';

import type { App } from 'vue';

import { createPinia } from 'pinia';

let pinia: Pinia;
const activeStores = new Set<StoreGeneric>();

export interface InitStoreOptions {
  namespace: string;
}

/**
 * @zh_CN 初始化pinia
 */
export async function initStores(app: App, options: InitStoreOptions) {
  const { createPersistedState } = await import('pinia-plugin-persistedstate');
  pinia = createPinia();
  activeStores.clear();
  pinia.use(({ store }) => {
    activeStores.add(store);
    const dispose = store.$dispose.bind(store);
    store.$dispose = () => {
      activeStores.delete(store);
      dispose();
    };
  });
  const { namespace } = options;
  pinia.use(
    createPersistedState({
      key: (storeKey) => `${namespace}-${storeKey}`,
      storage: localStorage,
    }),
  );
  app.use(pinia);
  return pinia;
}

export function resetAllStores() {
  if (!pinia) {
    console.error('Pinia is not installed');
    return;
  }
  for (const store of activeStores) {
    store.$reset();
  }
}
