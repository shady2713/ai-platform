package com.basicframework.module.system.controller.admin.user.vo.user;

import cn.hutool.core.util.ObjectUtil;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.validation.CodePointLength;
import com.basicframework.framework.common.validation.EmailEx;
import com.basicframework.framework.common.validation.InEnum;
import com.basicframework.framework.common.validation.Mobile;
import com.basicframework.framework.common.validation.Nickname;
import com.basicframework.framework.common.validation.Password;
import com.basicframework.framework.common.validation.Username;
import com.basicframework.module.system.enums.common.SexEnum;
import com.basicframework.module.system.framework.operatelog.core.DeptParseFunction;
import com.basicframework.module.system.framework.operatelog.core.EmailDesensitizeParseFunction;
import com.basicframework.module.system.framework.operatelog.core.MobileDesensitizeParseFunction;
import com.basicframework.module.system.framework.operatelog.core.PostParseFunction;
import com.basicframework.module.system.framework.operatelog.core.SexParseFunction;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mzt.logapi.starter.annotation.DiffLogField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.Data;
import lombok.ToString;

@Schema(description = "管理后台 - 用户创建/修改 Request VO")
@Data
@ToString(exclude = {"password", "email", "mobile"})
public class UserSaveReqVO {

    @Schema(description = "用户编号", example = "1024")
    @jakarta.validation.constraints.Positive(message = "用户编号必须大于 0")
    private Long id;

    @Schema(description = "用户账号", requiredMode = Schema.RequiredMode.REQUIRED, example = "basicframework")
    @NotBlank(message = "用户账号不能为空")
    @Username
    @DiffLogField(name = "用户账号")
    private String username;

    @Schema(description = "用户昵称", requiredMode = Schema.RequiredMode.REQUIRED, example = "basicframework")
    @NotBlank(message = "用户昵称不能为空")
    @Nickname
    @DiffLogField(name = "用户昵称")
    private String nickname;

    @Schema(description = "备注", example = "我是一个用户")
    @CodePointLength(max = 500, message = "备注长度不能超过 500 个字符")
    @DiffLogField(name = "备注")
    private String remark;

    @Schema(description = "部门编号", example = "1")
    @DiffLogField(name = "部门", function = DeptParseFunction.NAME)
    private Long deptId;

    @Schema(description = "岗位编号数组", example = "1")
    @DiffLogField(name = "岗位", function = PostParseFunction.NAME)
    private Set<Long> postIds;

    @Schema(description = "用户邮箱", example = "basicframework@example.com")
    @EmailEx
    @Size(max = 50, message = "邮箱长度不能超过 50 个字符")
    @DiffLogField(name = "用户邮箱", function = EmailDesensitizeParseFunction.NAME)
    private String email;

    @Schema(description = "手机号", example = "13812345678")
    @Mobile
    @DiffLogField(name = "手机号", function = MobileDesensitizeParseFunction.NAME)
    private String mobile;

    @Schema(description = "用户性别，参见 SexEnum 枚举类", example = "1")
    @InEnum(value = SexEnum.class, message = "用户性别必须是 {value}")
    @DiffLogField(name = "用户性别", function = SexParseFunction.NAME)
    private Integer sex;

    @Schema(description = "用户头像", example = "https://www.example.com/xxx.png")
    @DiffLogField(name = "用户头像")
    private String avatar;

    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "correct horse battery staple")
    @Password
    private String password;

    @Schema(description = "用户状态（仅新增时生效；编辑接口忽略该字段，禁用/启用请使用 update-status 接口），参见 CommonStatusEnum 枚举类", example = "0")
    @InEnum(value = CommonStatusEnum.class, message = "用户状态必须是 {value}")
    @DiffLogField(name = "用户状态")
    private Integer status;

    @AssertTrue(message = "新增用户时密码不能为空")
    @JsonIgnore
    public boolean isPasswordValid() {
        return id != null || ObjectUtil.isAllNotEmpty(password);
    }
}
