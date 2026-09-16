import type { ComputedRef, Ref } from 'vue';

import { computed, getCurrentInstance, unref, useAttrs, useSlots } from 'vue';

import {
  getFirstNonNullOrUndefined,
  kebabToCamelCase,
} from '@vben-core/shared/utils';

/**
 * 依次从插槽、attrs、props、state 中获取值
 * @param key
 * @param props
 * @param state
 */
export function usePriorityValue<
  T extends object,
  S extends object,
  K extends keyof T = keyof T,
>(key: K, props: T, state: Readonly<Ref<NoInfer<S>>> | undefined) {
  const instance = getCurrentInstance();
  const slots = useSlots();
  const attrs = useAttrs() as T;

  const value = computed((): T[K] => {
    // props不管有没有传，都会有默认值，会影响这里的顺序，
    // 通过判断原始props是否有值来判断是否传入
    const rawProps = (instance?.vnode?.props || {}) as T;

    const standardRawProps = Object.fromEntries(
      Object.entries(rawProps).map(([rawKey, rawValue]) => [
        kebabToCamelCase(rawKey),
        rawValue,
      ]),
    ) as Partial<T>;
    const currentProp = props[key];
    const propsKey =
      standardRawProps[key] === undefined ? undefined : currentProp;

    // slot可以关闭
    return getFirstNonNullOrUndefined<unknown>(
      slots[key as string],
      attrs[key],
      propsKey,
      state?.value?.[key as unknown as keyof S],
    ) as T[K];
  });

  return value;
}

/**
 * 批量获取state中的值（每个值都是ref）
 * @param props
 * @param state
 */
export function usePriorityValues<
  T extends object,
  S extends object = Partial<T>,
>(props: T, state: Readonly<Ref<S>> | undefined) {
  const result: { [K in keyof T]: ComputedRef<T[K]> } = {} as never;

  (Object.keys(props) as (keyof T)[]).forEach((key) => {
    result[key] = usePriorityValue(key as keyof typeof props, props, state);
  });

  return result;
}

/**
 * 批量获取state中的值（集中在一个computed，用于透传）
 * @param props
 * @param state
 */
export function useForwardPriorityValues<
  T extends object,
  S extends object = Partial<T>,
>(props: T, state: Readonly<Ref<S>> | undefined) {
  const computedResult: { [K in keyof T]: ComputedRef<T[K]> } = {} as never;

  (Object.keys(props) as (keyof T)[]).forEach((key) => {
    computedResult[key] = usePriorityValue(
      key as keyof typeof props,
      props,
      state,
    );
  });

  return computed(() => {
    return Object.fromEntries(
      (Object.keys(props) as Array<keyof T>).map((key) => [
        key,
        unref(computedResult[key]),
      ]),
    ) as { [K in keyof T]: T[K] };
  });
}
