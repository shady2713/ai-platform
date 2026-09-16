package com.basicframework.module.system.controller.admin.user.vo.profile;

import com.basicframework.framework.common.validation.EmailEx;
import com.basicframework.framework.common.validation.InEnum;
import com.basicframework.framework.common.validation.Mobile;
import com.basicframework.framework.common.validation.Nickname;
import com.basicframework.module.system.enums.common.SexEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import org.hibernate.validator.constraints.URL;

@Schema(description = "管理后台 - 用户个人信息更新 Request VO")
@Data
public class UserProfileUpdateReqVO {

    @Schema(description = "用户昵称", example = "basicframework")
    @NotBlank(message = "用户昵称不能为空")
    @Nickname
    private String nickname;

    @Schema(description = "用户邮箱", example = "basicframework@example.com")
    @EmailEx
    @Size(max = 50, message = "邮箱长度不能超过 50 个字符")
    @ToString.Exclude
    private String email;

    @Schema(description = "手机号", example = "13812345678")
    @Mobile
    @ToString.Exclude
    private String mobile;

    @Schema(description = "用户性别，参见 SexEnum 枚举类", example = "1")
    @InEnum(value = SexEnum.class, message = "用户性别必须是 {value}")
    private Integer sex;

    @Schema(description = "角色头像", example = "https://www.example.com/1.png")
    @URL(message = "头像地址格式不正确")
    private String avatar;
}
