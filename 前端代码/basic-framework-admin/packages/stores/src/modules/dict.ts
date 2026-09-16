import { acceptHMRUpdate, defineStore } from 'pinia';

/** 字典项数据结构 */
export interface DictItem {
  colorType?: string;
  cssClass?: string;
  label: string;
  value: string;
}

/** 字典类型到字典项数组的映射 */
export type Dict = Record<string, DictItem[]>;

interface DictState {
  dictCache: Dict;
}

interface DictApiItem extends Record<string, unknown> {
  colorType?: string;
  cssClass?: string;
  dictType: string;
}

type DictApiParams = Record<string, unknown>;

export const useDictStore = defineStore('core-dict', {
  actions: {
    getDictData(dictType: string, value: unknown) {
      const dict = this.dictCache[dictType];
      if (!dict) {
        return undefined;
      }
      const normalizedValue = typeof value === 'string' ? value : String(value);
      return dict.find((item) => item.value === normalizedValue);
    },
    getDictOptions(dictType: string) {
      const dictOptions = this.dictCache[dictType];
      if (!dictOptions) {
        return [];
      }
      return dictOptions;
    },
    setDictCache(dicts: Dict) {
      this.dictCache = dicts;
    },
    setDictCacheByApi(
      api: (params: DictApiParams) => Promise<DictApiItem[]>,
      params: DictApiParams = {},
      labelField: string = 'label',
      valueField: string = 'value',
    ) {
      return api(params).then((dicts) => {
        const dictCacheData: Dict = {};
        dicts.forEach((dict) => {
          dictCacheData[dict.dictType] = dicts
            .filter((d) => d.dictType === dict.dictType)
            .map((d) => ({
              colorType: d.colorType,
              cssClass: d.cssClass,
              label: String(d[labelField] ?? ''),
              value: String(d[valueField] ?? ''),
            }));
        });
        this.setDictCache(dictCacheData);
      });
    },
  },
  persist: {
    // 持久化
    pick: ['dictCache'],
  },
  state: (): DictState => ({
    dictCache: {},
  }),
});

// 解决热更新问题
const hot = import.meta.hot;
if (hot) {
  hot.accept(acceptHMRUpdate(useDictStore, hot));
}
