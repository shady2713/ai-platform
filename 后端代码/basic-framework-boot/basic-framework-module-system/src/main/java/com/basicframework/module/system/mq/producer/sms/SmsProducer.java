package com.basicframework.module.system.mq.producer.sms;

import com.basicframework.framework.common.core.KeyValue;
import com.basicframework.module.system.mq.message.sms.SmsSendMessage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Sms 短信相关消息的 Producer
 *
 * @since 2021/3/9 16:35
 */
@Component
@RequiredArgsConstructor
public class SmsProducer {

    private final ApplicationContext applicationContext;

    /**
     * 发送 {@link SmsSendMessage} 消息
     *
     * @param logId 短信日志编号
     * @param mobile 手机号
     * @param channelId 渠道编号
     * @param apiTemplateId 短信模板编号
     * @param templateParams 短信模板参数
     */
    public void sendSmsSendMessage(
            Long logId,
            String mobile,
            Long channelId,
            String apiTemplateId,
            List<KeyValue<String, Object>> templateParams) {
        SmsSendMessage message = new SmsSendMessage().setLogId(logId).setMobile(mobile);
        message.setChannelId(channelId).setApiTemplateId(apiTemplateId).setTemplateParams(templateParams);
        applicationContext.publishEvent(message);
    }
}
