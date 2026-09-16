package com.basicframework.module.system.enums.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link SmsTemplateTypeEnum} 单元测试
 *
 */
class SmsTemplateTypeEnumTest {

    @Test
    void values_publishStableTypesInDeclarationOrder() {
        assertThat(SmsTemplateTypeEnum.values())
                .extracting(SmsTemplateTypeEnum::getType)
                .containsExactly(1, 2, 3);
    }

    @Test
    void constants_carryExpectedSemanticTypes() {
        assertThat(SmsTemplateTypeEnum.VERIFICATION_CODE.getType()).isEqualTo(1);
        assertThat(SmsTemplateTypeEnum.NOTICE.getType()).isEqualTo(2);
        assertThat(SmsTemplateTypeEnum.PROMOTION.getType()).isEqualTo(3);
    }
}
