package com.basicframework.module.system.controller.admin.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.basicframework.module.system.framework.sms.config.SmsCallbackAuthenticator;
import com.basicframework.module.system.service.sms.SmsSendService;
import org.junit.jupiter.api.Test;

class SmsCallbackControllerTest {

    private final SmsSendService smsSendService = mock(SmsSendService.class);
    private final SmsCallbackAuthenticator authenticator = mock(SmsCallbackAuthenticator.class);
    private final SmsCallbackController controller = new SmsCallbackController(smsSendService, authenticator);

    @Test
    void aliyunCallbackAuthenticatesAndReturnsProviderContract() throws Throwable {
        SmsCallbackController.AliyunCallbackResponse response =
                controller.receiveAliyunSmsStatus("header-token", null, "[]");

        verify(authenticator).authenticate("header-token");
        verify(authenticator).validatePayload("[]");
        verify(smsSendService).receiveSmsStatus("ALIYUN", "[]");
        assertThat(response.code()).isZero();
        assertThat(response.msg()).isEqualTo("success");
    }

    @Test
    void aliyunCallbackUsesQueryFallbackAndReturnsProviderContract() throws Throwable {
        SmsCallbackController.AliyunCallbackResponse response =
                controller.receiveAliyunSmsStatus(null, "query-token", "[]");

        verify(authenticator).authenticate("query-token");
        verify(authenticator).validatePayload("[]");
        verify(smsSendService).receiveSmsStatus("ALIYUN", "[]");
        assertThat(response.code()).isZero();
        assertThat(response.msg()).isEqualTo("success");
    }
}
