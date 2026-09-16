package com.basicframework.module.system.service.auth.dto;

import com.basicframework.framework.common.validation.InEnum;
import com.basicframework.framework.common.validation.Mobile;
import com.basicframework.module.system.enums.sms.SmsSceneEnum;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 管理后台 - 发送手机验证码参数 DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"mobile"})
public class AuthSmsSendDTO extends CaptchaVerificationDTO {

    /**
     * 手机号
     */
    @NotEmpty(message = "手机号不能为空")
    @Mobile
    private String mobile;

    /**
     * 短信场景
     */
    @NotNull(message = "发送场景不能为空")
    @InEnum(SmsSceneEnum.class)
    private Integer scene;
}
