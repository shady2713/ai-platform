package com.basicframework.module.system.enums.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SmsSceneEnumTest {

    @Test
    void exposesOnlySupportedAdminSmsScenes() {
        assertThat(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.getDescription()).isEqualTo("后台用户 - 忘记密码");
        assertThat(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD.array()).containsExactly(23);
        assertThat(SmsSceneEnum.getCodeByScene(23)).isEqualTo(SmsSceneEnum.ADMIN_MEMBER_RESET_PASSWORD);
        assertThat(SmsSceneEnum.getCodeByScene(999)).isNull();
    }
}
