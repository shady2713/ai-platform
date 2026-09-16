package com.basicframework.module.system.enums.sms;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 短信模板类型枚举
 *
 */
@Getter
@AllArgsConstructor
public enum SmsTemplateTypeEnum {

    /** 验证码 */
    VERIFICATION_CODE(1),
    /** 通知 */
    NOTICE(2),
    /** 营销 */
    PROMOTION(3),
    ;

    /**
     * 类型
     */
    private final int type;
}
