package com.basicframework.module.system.service.auth.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;

/**
 * 管理后台 - 认证前修改过期密码参数 DTO
 */
@Data
@ToString(exclude = {"oldPassword", "newPassword"})
public class AuthChangeExpiredPasswordDTO {

    /**
     * 用户账号
     */
    @NotEmpty(message = "登录账号不能为空")
    private String username;

    /**
     * 原密码
     */
    @NotEmpty(message = "原密码不能为空")
    private String oldPassword;

    /**
     * 新密码
     */
    @NotEmpty(message = "新密码不能为空")
    private String newPassword;
}
