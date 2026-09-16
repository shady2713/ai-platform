package com.basicframework.module.system.enums.permission;

import com.basicframework.framework.common.core.ArrayValuable;
import java.util.Arrays;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 菜单类型枚举类
 *
 */
@Getter
@AllArgsConstructor
public enum MenuTypeEnum implements ArrayValuable<Integer> {

    /** 目录 */
    DIR(1),
    /** 菜单 */
    MENU(2),
    /** 按钮 */
    BUTTON(3);

    private static final Integer[] VALUES =
            Arrays.stream(values()).map(MenuTypeEnum::getType).toArray(Integer[]::new);

    /**
     * 类型
     */
    private final Integer type;

    @Override
    public Integer[] array() {
        return VALUES.clone();
    }
}
