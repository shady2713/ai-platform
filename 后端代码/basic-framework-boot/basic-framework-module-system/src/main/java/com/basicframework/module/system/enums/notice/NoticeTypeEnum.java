package com.basicframework.module.system.enums.notice;

import com.basicframework.framework.common.core.ArrayValuable;
import java.util.Arrays;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 公告类型。 */
@Getter
@AllArgsConstructor
public enum NoticeTypeEnum implements ArrayValuable<Integer> {
    NOTICE(1),
    ANNOUNCEMENT(2);

    private static final Integer[] VALUES =
            Arrays.stream(values()).map(NoticeTypeEnum::getType).toArray(Integer[]::new);

    private final Integer type;

    @Override
    public Integer[] array() {
        return VALUES.clone();
    }
}
