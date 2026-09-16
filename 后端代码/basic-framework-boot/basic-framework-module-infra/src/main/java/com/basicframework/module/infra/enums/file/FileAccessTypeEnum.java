package com.basicframework.module.infra.enums.file;

import com.basicframework.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 文件读取策略。 */
@AllArgsConstructor
@Getter
public enum FileAccessTypeEnum implements ArrayValuable<Integer> {
    /** 可由匿名请求读取。 */
    PUBLIC(1, "公开读取"),
    /** 仅文件所有者或文件管理员可读取。 */
    PRIVATE(2, "私有读取");

    private static final Integer[] VALUES = {PUBLIC.value, PRIVATE.value};

    private final Integer value;
    private final String name;

    public static FileAccessTypeEnum fromPublicRead(boolean publicRead) {
        return publicRead ? PUBLIC : PRIVATE;
    }

    public static boolean isPublic(Integer value) {
        return PUBLIC.value.equals(value);
    }

    public static boolean isValid(Integer value) {
        return PUBLIC.value.equals(value) || PRIVATE.value.equals(value);
    }

    @Override
    public Integer[] array() {
        return VALUES.clone();
    }
}
