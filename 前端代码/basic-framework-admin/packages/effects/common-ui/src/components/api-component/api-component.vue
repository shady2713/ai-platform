<script lang="ts" setup>
import type { Component } from 'vue';

import { computed, nextTick, ref, unref, useAttrs, watch } from 'vue';

import { LoaderCircle } from '@vben/icons';

import { cloneDeep, get, isEqual, isFunction } from '@vben-core/shared/utils';

import { objectOmit } from '@vueuse/core';

type OptionsItem = {
  [name: string]: unknown;
  children?: OptionsItem[];
  disabled?: boolean;
  label?: unknown;
  value?: unknown;
};

type ApiParams = Record<string, unknown>;
type ApiResult = OptionsItem[] | Record<string, unknown>;
type MaybePromise<T> = PromiseLike<T> | T;

interface Props {
  /** 组件 */
  component: Component;
  /** 是否将value从数字转为string */
  numberToString?: boolean;
  /** 获取options数据的函数 */
  api?: (arg?: ApiParams) => Promise<ApiResult>;
  /** 传递给api的参数 */
  params?: ApiParams;
  /** 从api返回的结果中提取options数组的字段名 */
  resultField?: string;
  /** label字段名 */
  labelField?: string;
  /** children字段名，需要层级数据的组件可用 */
  childrenField?: string;
  /** value字段名 */
  valueField?: string;
  /** disabled字段名 */
  disabledField?: string;
  /** 组件接收options数据的属性名 */
  optionsPropName?: string;
  /** 是否立即调用api */
  immediate?: boolean;
  /** 每次`visibleEvent`事件发生时都重新请求数据 */
  alwaysLoad?: boolean;
  /** 在api请求之前的回调函数 */
  beforeFetch?: (params: ApiParams) => MaybePromise<ApiParams | undefined>;
  /** 在api请求之后的回调函数 */
  afterFetch?: (result: ApiResult) => MaybePromise<ApiResult | undefined>;
  /** 直接传入选项数据，也作为api返回空数据时的后备数据 */
  options?: OptionsItem[];
  /** 组件的插槽名称，用来显示一个"加载中"的图标 */
  loadingSlot?: string;
  /** 触发api请求的事件名 */
  visibleEvent?: string;
  /** 组件的v-model属性名，默认为modelValue。部分组件可能为value */
  modelPropName?: string;
  /**
   * 自动选择
   * - `first`：自动选择第一个选项
   * - `last`：自动选择最后一个选项
   * - `one`: 当请求的结果只有一个选项时，自动选择该选项
   * - 函数：自定义选择逻辑，函数的参数为请求的结果数组，返回值为选择的选项
   * - false：不自动选择(默认)
   */
  autoSelect?:
    | 'first'
    | 'last'
    | 'one'
    | ((item: OptionsItem[]) => OptionsItem | undefined)
    | false;
}

defineOptions({ name: 'ApiComponent', inheritAttrs: false });

const props = withDefaults(defineProps<Props>(), {
  labelField: 'label',
  valueField: 'value',
  disabledField: 'disabled',
  childrenField: '',
  optionsPropName: 'options',
  resultField: '',
  visibleEvent: '',
  numberToString: false,
  params: () => ({}),
  immediate: true,
  alwaysLoad: false,
  loadingSlot: '',
  beforeFetch: undefined,
  afterFetch: undefined,
  modelPropName: 'modelValue',
  api: undefined,
  autoSelect: false,
  options: () => [],
});

const emit = defineEmits<{
  fetchError: [error: unknown];
  optionsChange: [OptionsItem[]];
}>();

const modelValue = defineModel<unknown>({ default: undefined });

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function normalizeOptions(value: unknown): OptionsItem[] {
  return Array.isArray(value) ? value.filter((item) => isRecord(item)) : [];
}

const attrs = useAttrs();
const innerParams = ref<ApiParams>({});
const refOptions = ref<OptionsItem[]>([]);
const loading = ref(false);
// 首次是否加载过了
const isFirstLoaded = ref(false);
// 标记是否有待处理的请求
const hasPendingRequest = ref(false);

const getOptions = computed(() => {
  const {
    labelField,
    valueField,
    disabledField,
    childrenField,
    numberToString,
  } = props;

  const refOptionsData = unref(refOptions);

  function transformData(data: OptionsItem[]): OptionsItem[] {
    return data.map((item) => {
      const value = get(item, valueField);
      const disabled = get(item, disabledField);
      const children = childrenField ? item[childrenField] : undefined;
      return {
        ...objectOmit(item, [
          labelField,
          valueField,
          disabledField,
          childrenField,
        ]),
        label: get(item, labelField),
        value: numberToString && typeof value === 'number' ? `${value}` : value,
        disabled: typeof disabled === 'boolean' ? disabled : undefined,
        ...(childrenField && Array.isArray(children)
          ? { children: transformData(normalizeOptions(children)) }
          : {}),
      };
    });
  }

  const data: OptionsItem[] = transformData(refOptionsData);

  return data.length > 0 ? data : props.options;
});

const bindProps = computed(() => {
  return {
    [props.modelPropName]: unref(modelValue),
    [props.optionsPropName]: unref(getOptions),
    [`onUpdate:${props.modelPropName}`]: (val: unknown) => {
      modelValue.value = val;
    },
    ...objectOmit(attrs, [`onUpdate:${props.modelPropName}`]),
    ...(props.visibleEvent
      ? {
          [props.visibleEvent]: handleFetchForVisible,
        }
      : {}),
  };
});

async function fetchApi() {
  const { api, beforeFetch, afterFetch, resultField } = props;

  if (!api || !isFunction(api)) {
    return;
  }

  // 如果正在加载，标记有待处理的请求并返回
  if (loading.value) {
    hasPendingRequest.value = true;
    return;
  }

  refOptions.value = [];
  try {
    loading.value = true;
    let finalParams = unref(mergedParams);
    if (beforeFetch && isFunction(beforeFetch)) {
      const transformedParams = await beforeFetch(
        cloneDeep(finalParams) as ApiParams,
      );
      if (isRecord(transformedParams)) {
        finalParams = transformedParams;
      }
    }
    let res = await api(finalParams);
    if (afterFetch && isFunction(afterFetch)) {
      const transformedResult = await afterFetch(res);
      if (Array.isArray(transformedResult) || isRecord(transformedResult)) {
        res = transformedResult;
      }
    }
    isFirstLoaded.value = true;
    if (Array.isArray(res)) {
      refOptions.value = normalizeOptions(res);
      emitChange();
      return;
    }
    if (resultField) {
      refOptions.value = normalizeOptions(get(res, resultField));
    }
    emitChange();
  } catch (error) {
    isFirstLoaded.value = false;
    emit('fetchError', error);
  } finally {
    loading.value = false;
    // 如果有待处理的请求，立即触发新的请求
    if (hasPendingRequest.value) {
      hasPendingRequest.value = false;
      // 使用 nextTick 确保状态更新完成后再触发新请求
      await nextTick();
      await fetchApi();
    }
  }
}

async function handleFetchForVisible(visible: boolean) {
  if (visible) {
    if (props.alwaysLoad) {
      await fetchApi();
    } else if (!props.immediate && !unref(isFirstLoaded)) {
      await fetchApi();
    }
  }
}

const mergedParams = computed(() => {
  return {
    ...props.params,
    ...unref(innerParams),
  };
});

watch(
  mergedParams,
  (value, oldValue) => {
    if (isEqual(value, oldValue)) {
      return;
    }
    void fetchApi();
  },
  { deep: true, immediate: props.immediate },
);

function emitChange() {
  if (
    modelValue.value === undefined &&
    props.autoSelect &&
    unref(getOptions).length > 0
  ) {
    let firstOption;
    if (isFunction(props.autoSelect)) {
      firstOption = props.autoSelect(unref(getOptions));
    } else {
      switch (props.autoSelect) {
        case 'first': {
          firstOption = unref(getOptions)[0];
          break;
        }
        case 'last': {
          firstOption = unref(getOptions)[unref(getOptions).length - 1];
          break;
        }
        case 'one': {
          if (unref(getOptions).length === 1) {
            firstOption = unref(getOptions)[0];
          }
          break;
        }
      }
    }

    if (firstOption) modelValue.value = firstOption.value;
  }
  emit('optionsChange', unref(getOptions));
}
const componentRef = ref<unknown>();
defineExpose({
  /** 获取options数据 */
  getOptions: () => unref(getOptions),
  /** 获取当前值 */
  getValue: () => unref(modelValue),
  /** 获取被包装的组件实例 */
  getComponentRef: <T = unknown,>() => componentRef.value as T,
  /** 更新Api参数 */
  updateParam(newParams: ApiParams) {
    innerParams.value = newParams;
  },
});
</script>
<template>
  <component
    :is="component"
    v-bind="bindProps"
    :placeholder="$attrs.placeholder"
    ref="componentRef"
  >
    <template v-for="item in Object.keys($slots)" #[item]="data">
      <slot :name="item" v-bind="data || {}"></slot>
    </template>
    <template v-if="loadingSlot && loading" #[loadingSlot]>
      <LoaderCircle class="animate-spin" />
    </template>
  </component>
</template>
