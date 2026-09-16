package com.basicframework.module.system.controller.admin.user.vo.profile;

import com.basicframework.framework.common.validation.Password;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;

@Schema(description = "管理后台 - 用户个人中心更新密码 Request VO")
@Data
@ToString(exclude = {"oldPassword", "newPassword"})
public class UserProfileUpdatePasswordReqVO {

    @Schema(description = "旧密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "oldPassword")
    @NotEmpty(message = "旧密码不能为空")
    private String oldPassword;

    @Schema(description = "新密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "correct horse battery staple")
    @NotEmpty(message = "新密码不能为空")
    @Password
    private String newPassword;
}
