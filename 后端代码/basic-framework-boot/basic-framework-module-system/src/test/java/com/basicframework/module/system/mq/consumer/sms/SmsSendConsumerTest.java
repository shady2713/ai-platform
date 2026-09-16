package com.basicframework.module.system.mq.consumer.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.basicframework.module.system.mq.message.sms.SmsSendMessage;
import com.basicframework.module.system.service.sms.SmsSendService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SmsSendConsumer} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class SmsSendConsumerTest {

    @InjectMocks
    private SmsSendConsumer consumer;

    @Mock
    private SmsSendService smsSendService;

    @Test
    void onMessage_delegatesToSendService() {
        SmsSendMessage message = new SmsSendMessage();
        message.setLogId(1L);
        message.setChannelId(2L);
        message.setMobile("13800138000");
        message.setApiTemplateId("template-1");

        consumer.onMessage(message);

        verify(smsSendService).doSendSms(message);
    }

    @Test
    void onMessage_handlesNullAndShortMobilesWithoutFailure() {
        SmsSendMessage nullMobile = message(null);
        SmsSendMessage shortMobile = message("123456");

        consumer.onMessage(nullMobile);
        consumer.onMessage(shortMobile);

        verify(smsSendService, times(2)).doSendSms(any(SmsSendMessage.class));
    }

    @Test
    void maskMobile_masksMiddleDigitsAndDegradesGracefully() throws Exception {
        Method maskMobile = SmsSendConsumer.class.getDeclaredMethod("maskMobile", String.class);
        maskMobile.setAccessible(true);

        assertThat(maskMobile.invoke(consumer, "13800138000")).isEqualTo("138****8000");
        assertThat(maskMobile.invoke(consumer, "12345")).isEqualTo("***");
        assertThat(maskMobile.invoke(consumer, (Object) null)).isEqualTo("***");
    }

    private static SmsSendMessage message(String mobile) {
        SmsSendMessage message = new SmsSendMessage();
        message.setLogId(1L);
        message.setChannelId(2L);
        message.setMobile(mobile);
        message.setApiTemplateId("template-1");
        return message;
    }
}
