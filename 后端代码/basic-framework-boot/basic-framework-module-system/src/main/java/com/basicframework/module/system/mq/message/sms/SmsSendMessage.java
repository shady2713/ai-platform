package com.basicframework.module.system.mq.message.sms;

import com.basicframework.framework.common.core.KeyValue;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;
import lombok.ToString;

/**
 * 短信发送消息
 *
 */
@Data
public class SmsSendMessage {

    /**
     * 短信日志编号
     */
    @NotNull(message = "短信日志编号不能为空")
    private Long logId;
    /**
     * 手机号
     */
    @NotNull(message = "手机号不能为空")
    @ToString.Exclude
    private String mobile;
    /**
     * 短信渠道编号
     */
    @NotNull(message = "短信渠道编号不能为空")
    private Long channelId;
    /**
     * 短信 API 的模板编号
     */
    @NotNull(message = "短信 API 的模板编号不能为空")
    private String apiTemplateId;
    /**
     * 短信模板参数
     */
    private List<KeyValue<String, Object>> templateParams;
}
