package com.basicframework.module.system.service.auth.dto;

import com.basicframework.framework.common.validation.Mobile;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;

/**
 * 管理后台 - 短信重置账号密码参数 DTO
 */
@Data
@ToString(exclude = {"password", "mobile", "code"})
public class AuthResetPasswordDTO {

    /**
     * 密码
     */
    @NotEmpty(message = "密码不能为空")
    private String password;

    /**
     * 手机号
     */
    @NotEmpty(message = "手机号不能为空")
    @Mobile
    private String mobile;

    /**
     * 手机短信验证码
     */
    @NotEmpty(message = "手机短信验证码不能为空")
    private String code;
}
