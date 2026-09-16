package com.basicframework.module.system.controller.admin.auth.vo;

import com.basicframework.framework.common.validation.Password;
import com.basicframework.framework.common.validation.Username;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Schema(description = "管理后台 - 认证前修改过期密码 Request VO")
@Data
@ToString(exclude = {"oldPassword", "newPassword"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthChangeExpiredPasswordReqVO {

    @Schema(description = "账号", requiredMode = Schema.RequiredMode.REQUIRED, example = "basicframework")
    @NotEmpty(message = "登录账号不能为空")
    @Username
    private String username;

    @Schema(description = "原密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "correct horse battery staple")
    @NotEmpty(message = "原密码不能为空")
    private String oldPassword;

    @Schema(description = "新密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "even longer passphrase 2026")
    @NotEmpty(message = "新密码不能为空")
    @Password
    private String newPassword;
}
