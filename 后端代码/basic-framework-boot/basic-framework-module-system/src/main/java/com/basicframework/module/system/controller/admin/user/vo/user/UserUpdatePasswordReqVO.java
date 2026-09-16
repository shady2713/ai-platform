package com.basicframework.module.system.controller.admin.user.vo.user;

import com.basicframework.framework.common.validation.Password;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.ToString;

@Schema(description = "管理后台 - 用户更新密码 Request VO")
@Data
@ToString(exclude = {"password"})
public class UserUpdatePasswordReqVO {

    @Schema(description = "用户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "用户编号不能为空")
    @Positive(message = "用户编号必须大于 0")
    private Long id;

    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "correct horse battery staple")
    @NotEmpty(message = "密码不能为空")
    @Password
    private String password;
}
