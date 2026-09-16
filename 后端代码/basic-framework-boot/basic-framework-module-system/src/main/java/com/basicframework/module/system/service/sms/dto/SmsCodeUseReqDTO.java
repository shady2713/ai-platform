package com.basicframework.module.system.service.sms.dto;

import com.basicframework.framework.common.validation.InEnum;
import com.basicframework.framework.common.validation.Mobile;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;

/** 短信验证码消费参数。 */
@Data
@ToString(exclude = {"mobile", "code", "usedIp"})
public class SmsCodeUseReqDTO {

    @Mobile
    @NotEmpty(message = "手机号不能为空")
    private String mobile;

    @NotNull(message = "发送场景不能为空")
    @InEnum(SmsSceneEnum.class)
    private Integer scene;

    @NotEmpty(message = "验证码不能为空")
    private String code;

    @NotEmpty(message = "使用 IP 不能为空")
    private String usedIp;
}
