package com.basicframework.module.system.enums.permission;

import com.basicframework.framework.common.core.ArrayValuable;
import java.util.Arrays;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据权限范围的枚举
 *
 * 用于实现数据级别的权限
 *
 */
@Getter
@AllArgsConstructor
public enum DataScopeEnum implements ArrayValuable<Integer> {

    /** 全部数据权限 */
    ALL(1),

    /** 指定部门数据权限 */
    DEPT_CUSTOM(2),
    /** 部门数据权限 */
    DEPT_ONLY(3),
    /** 部门及以下数据权限 */
    DEPT_AND_CHILD(4),

    /** 仅本人数据权限 */
    SELF(5);

    /**
     * 范围
     */
    private final Integer scope;

    public static final Integer[] ARRAYS =
            Arrays.stream(values()).map(DataScopeEnum::getScope).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    public static DataScopeEnum fromScope(Integer scope) {
        return Arrays.stream(values())
                .filter(value -> Objects.equals(value.getScope(), scope))
                .findFirst()
                .orElse(null);
    }
}
