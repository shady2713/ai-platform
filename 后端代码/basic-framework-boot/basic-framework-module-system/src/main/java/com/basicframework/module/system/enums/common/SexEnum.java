package com.basicframework.module.system.enums.common;

import com.basicframework.framework.common.core.ArrayValuable;
import java.util.Arrays;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 性别的枚举值
 *
 */
@Getter
@AllArgsConstructor
public enum SexEnum implements ArrayValuable<Integer> {

    /** 男 */
    MALE(1),
    /** 女 */
    FEMALE(2),
    /** 未知 */
    UNKNOWN(0);

    public static final Integer[] ARRAYS =
            Arrays.stream(values()).map(SexEnum::getSex).toArray(Integer[]::new);

    /**
     * 性别
     */
    private final Integer sex;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }
}
