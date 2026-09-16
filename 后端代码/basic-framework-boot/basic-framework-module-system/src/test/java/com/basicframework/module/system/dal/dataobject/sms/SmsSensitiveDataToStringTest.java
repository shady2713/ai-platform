package com.basicframework.module.system.dal.dataobject.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 短信领域对象不得通过 Lombok 自动字符串表示泄露个人信息或验证码。 */
class SmsSensitiveDataToStringTest {

    @Test
    void smsCodeDoesNotExposeVerificationCodeOrPersonalData() {
        SmsCodeDO smsCode = new SmsCodeDO();
        smsCode.setMobile("13900000001");
        smsCode.setCode("123456");
        smsCode.setCreateIp("192.0.2.10");
        smsCode.setUsedIp("192.0.2.11");

        assertThat(smsCode.toString()).doesNotContain("13900000001", "123456", "192.0.2.10", "192.0.2.11");
    }

    @Test
    void smsLogDoesNotExposeContentParametersOrMobile() {
        SmsLogDO smsLog = new SmsLogDO();
        smsLog.setMobile("13900000001");
        smsLog.setTemplateContent("您的验证码为 123456");
        smsLog.setTemplateParams(Map.of("code", "123456"));
        smsLog.setApiSendMsg("provider message 123456");
        smsLog.setApiReceiveMsg("receipt message 123456");

        assertThat(smsLog.toString())
                .doesNotContain("13900000001", "您的验证码为 123456", "provider message", "receipt message");
    }
}
