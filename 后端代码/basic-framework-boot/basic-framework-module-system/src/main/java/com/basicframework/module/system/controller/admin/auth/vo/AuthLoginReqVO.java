package com.basicframework.module.system.controller.admin.auth.vo;

import com.basicframework.framework.common.validation.Username;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Schema(description = "管理后台 - 账号密码登录 Request VO")
@Data
@ToString(exclude = {"password"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthLoginReqVO extends CaptchaVerificationReqVO {

    @Schema(description = "账号", requiredMode = Schema.RequiredMode.REQUIRED, example = "basicframework")
    @NotEmpty(message = "登录账号不能为空")
    @Username
    private String username;

    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "Abcd12")
    @NotEmpty(message = "密码不能为空")
    private String password;
}
