package com.basicframework.framework.common.util.collection;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** 项目实际使用的集合转换公共契约。 */
public final class CollectionUtils {

    private CollectionUtils() {}

    public static boolean containsAny(Object source, Object... targets) {
        return Arrays.asList(targets).contains(source);
    }

    public static <T> List<T> filterList(Collection<T> source, Predicate<T> predicate) {
        if (CollUtil.isEmpty(source)) {
            return new ArrayList<>();
        }
        return source.stream().filter(predicate).collect(Collectors.toList());
    }

    public static <T, U> List<U> convertList(T[] source, Function<T, U> converter) {
        if (ArrayUtil.isEmpty(source)) {
            return new ArrayList<>();
        }
        return convertList(Arrays.asList(source), converter);
    }

    public static <T, U> List<U> convertList(Collection<T> source, Function<T, U> converter) {
        if (CollUtil.isEmpty(source)) {
            return new ArrayList<>();
        }
        return source.stream().map(converter).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public static <T, U> List<U> convertList(Collection<T> source, Function<T, U> converter, Predicate<T> filter) {
        if (CollUtil.isEmpty(source)) {
            return new ArrayList<>();
        }
        return source.stream()
                .filter(filter)
                .map(converter)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public static <T, U> Set<U> convertSet(Collection<T> source, Function<T, U> converter) {
        if (CollUtil.isEmpty(source)) {
            return new HashSet<>();
        }
        return source.stream().map(converter).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public static <T, K> Map<K, T> convertMap(Collection<T> source, Function<T, K> keyConverter) {
        if (CollUtil.isEmpty(source)) {
            return new HashMap<>();
        }
        return source.stream().collect(Collectors.toMap(keyConverter, Function.identity(), (first, ignored) -> first));
    }

    public static <T> T findFirst(Collection<T> source, Predicate<T> predicate) {
        if (CollUtil.isEmpty(source)) {
            return null;
        }
        return source.stream().filter(predicate).findFirst().orElse(null);
    }

    public static <T> void addIfNotNull(Collection<T> collection, T item) {
        if (item != null) {
            collection.add(item);
        }
    }

    public static <T> Collection<T> singleton(T object) {
        return object == null ? Collections.emptyList() : Collections.singleton(object);
    }
}
