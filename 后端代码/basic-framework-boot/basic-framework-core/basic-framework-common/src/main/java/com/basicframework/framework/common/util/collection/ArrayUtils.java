package com.basicframework.framework.common.util.collection;

import static com.basicframework.framework.common.util.collection.CollectionUtils.convertList;

import cn.hutool.core.util.ArrayUtil;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Array 工具类
 *
 */
public class ArrayUtils {

    /**
     * 将 object 和 newElements 合并成一个数组
     *
     * @param object 对象
     * @param newElements 数组
     * @param <T> 泛型
     * @return 结果数组
     */
    @SafeVarargs
    public static <T> Consumer<T>[] append(Consumer<T> object, Consumer<T>... newElements) {
        if (object == null) {
            return newElements;
        }
        Consumer<T>[] result = ArrayUtil.newArray(Consumer.class, 1 + newElements.length);
        result[0] = object;
        System.arraycopy(newElements, 0, result, 1, newElements.length);
        return result;
    }

    /**
     * 转换集合元素并返回无运行时类型假设的数组。
     *
     * @param from 原始集合
     * @param mapper 元素转换函数
     * @param <T> 原始元素类型
     * @return 转换后的数组
     */
    public static <T> Object[] toArray(Collection<T> from, Function<T, ?> mapper) {
        return convertList(from, mapper).toArray();
    }

    public static <T> T get(T[] array, int index) {
        if (array == null || index < 0 || index >= array.length) {
            return null;
        }
        return array[index];
    }
}
