package com.basicframework.module.system.service.auth.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;

/**
 * 管理后台 - 验证码校验参数 DTO
 *
 * 承接 controller 层 CaptchaVerificationReqVO 的验证码字段与分组校验语义，
 * 供 service 层在开启验证码时做分组校验使用
 */
@Data
@ToString(exclude = {"captchaVerification"})
public class CaptchaVerificationDTO {

    /**
     * 验证码，验证码开启时，需要传递
     */
    @NotEmpty(message = "验证码不能为空", groups = CodeEnableGroup.class)
    private String captchaVerification;

    /**
     * 开启验证码的 Group
     */
    public interface CodeEnableGroup {}
}
