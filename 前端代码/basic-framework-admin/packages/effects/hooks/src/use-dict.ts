import { useDictStore } from '@vben/stores';
import { isObject } from '@vben/utils';

type ColorType = 'error' | 'info' | 'success' | 'warning';

export interface DictDataType {
  dictType?: string;
  label: string;
  value: boolean | number | string;
  colorType?: ColorType;
  cssClass?: string;
}

export interface NumberDictDataType extends DictDataType {
  value: number;
}

export interface StringDictDataType extends DictDataType {
  value: string;
}

/**
 * 获取字典标签
 *
 * @param dictType 字典类型
 * @param value 字典值
 * @returns 字典标签
 */
export function getDictLabel(dictType: string, value: unknown) {
  const dictStore = useDictStore();
  const dictObj = dictStore.getDictData(dictType, value);
  return isObject(dictObj) ? dictObj.label : '';
}

/**
 * 获取字典对象
 *
 * @param dictType 字典类型
 * @param value 字典值
 * @returns 字典对象
 */
export function getDictObj(dictType: string, value: unknown) {
  const dictStore = useDictStore();
  const dictObj = dictStore.getDictData(dictType, value);
  return isObject(dictObj) ? dictObj : null;
}

/**
 * 获取字典数组 用于select radio 等
 *
 * @param dictType 字典类型
 * @param valueType 字典值类型，默认 string 类型
 * @returns 字典数组
 * @throws {TypeError} 当运行时传入未支持的值类型或无效数字字典值时抛出
 */
export function getDictOptions(
  dictType: string,
  valueType: 'boolean' | 'number' | 'string' = 'string',
): DictDataType[] {
  const dictStore = useDictStore();
  return dictStore.getDictOptions(dictType).map((option) => ({
    label: option.label,
    value: convertDictValue(option.value, valueType),
  }));
}

function convertDictValue(
  value: string,
  valueType: 'boolean' | 'number' | 'string',
): boolean | number | string {
  switch (valueType) {
    case 'boolean': {
      if (value !== 'true' && value !== 'false') {
        throw new TypeError(`无效的布尔字典值：${value}`);
      }
      return value === 'true';
    }
    case 'number': {
      const numericValue = Number(value);
      if (value.trim() === '' || !Number.isFinite(numericValue)) {
        throw new TypeError(`无效的数字字典值：${value}`);
      }
      return numericValue;
    }
    case 'string': {
      return value;
    }
    default: {
      throw new TypeError(`不支持的字典值类型：${valueType}`);
    }
  }
}
