package com.basicframework.module.system.service.auth.dto;

import com.basicframework.framework.common.validation.Username;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 管理后台 - 账号密码登录参数 DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"password"})
public class AuthLoginDTO extends CaptchaVerificationDTO {

    /**
     * 账号
     */
    @NotEmpty(message = "登录账号不能为空")
    @Username
    private String username;

    /**
     * 密码
     */
    @NotEmpty(message = "密码不能为空")
    private String password;
}
