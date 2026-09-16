package com.basicframework.module.system.framework.sms.core.enums;

import cn.hutool.core.util.ArrayUtil;
import com.basicframework.framework.common.core.ArrayValuable;
import java.util.Arrays;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 短信渠道枚举
 *
 * @since 2021/1/25 10:56
 */
@Getter
@AllArgsConstructor
public enum SmsChannelEnum implements ArrayValuable<String> {
    ALIYUN("ALIYUN", "阿里云"),
    ;

    public static final String[] ARRAYS =
            Arrays.stream(values()).map(SmsChannelEnum::getCode).toArray(String[]::new);

    /**
     * 编码
     */
    private final String code;
    /**
     * 名字
     */
    private final String name;

    public static SmsChannelEnum getByCode(String code) {
        return ArrayUtil.firstMatch(o -> o.getCode().equals(code), values());
    }

    @Override
    public String[] array() {
        return ARRAYS;
    }
}
