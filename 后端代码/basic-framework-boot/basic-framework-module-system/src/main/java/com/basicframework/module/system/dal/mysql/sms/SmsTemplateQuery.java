package com.basicframework.module.system.dal.mysql.sms;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 短信模板分页查询条件
 */
@Getter
@AllArgsConstructor
public class SmsTemplateQuery {

    private final Integer type;
    private final Integer status;
    private final String code;
    private final String content;
    private final String apiTemplateId;
    private final Long channelId;
    private final LocalDateTime[] createTime;
}
