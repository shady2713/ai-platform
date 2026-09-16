package com.basicframework.framework.common.util.object;

import java.util.Arrays;

/** 项目实际使用的对象判断公共契约。 */
public final class ObjectUtils {

    private ObjectUtils() {}

    @SafeVarargs
    public static <T> boolean equalsAny(T object, T... candidates) {
        return Arrays.asList(candidates).contains(object);
    }
}
