package com.basicframework.module.system.mq.producer.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.basicframework.framework.common.core.KeyValue;
import com.basicframework.module.system.mq.message.sms.SmsSendMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

/**
 * {@link SmsProducer} 契约测试：消息整装后通过 Spring 事件发布，所有字段原样透传。
 */
class SmsProducerTest {

    @Test
    void sendSmsSendMessage_publishesFullyPopulatedMessage() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        SmsProducer producer = new SmsProducer(applicationContext);
        List<KeyValue<String, Object>> templateParams = List.of(new KeyValue<>("code", "123456"));

        producer.sendSmsSendMessage(1L, "13800000000", 2L, "template-1", templateParams);

        ArgumentCaptor<SmsSendMessage> messageCaptor = ArgumentCaptor.forClass(SmsSendMessage.class);
        verify(applicationContext).publishEvent(messageCaptor.capture());
        SmsSendMessage published = messageCaptor.getValue();
        assertThat(published.getLogId()).isEqualTo(1L);
        assertThat(published.getMobile()).isEqualTo("13800000000");
        assertThat(published.getChannelId()).isEqualTo(2L);
        assertThat(published.getApiTemplateId()).isEqualTo("template-1");
        assertThat(published.getTemplateParams()).isSameAs(templateParams);
    }
}
