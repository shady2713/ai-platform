package com.basicframework.module.system.enums.notify;

import com.basicframework.framework.common.core.ArrayValuable;
import java.util.Arrays;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通知模板类型枚举
 *
 */
@Getter
@AllArgsConstructor
public enum NotifyTemplateTypeEnum implements ArrayValuable<Integer> {

    /**
     * 系统消息
     */
    SYSTEM_MESSAGE(2),
    /**
     * 通知消息
     */
    NOTIFICATION_MESSAGE(1);

    public static final Integer[] ARRAYS =
            Arrays.stream(values()).map(NotifyTemplateTypeEnum::getType).toArray(Integer[]::new);

    private final Integer type;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }
}
