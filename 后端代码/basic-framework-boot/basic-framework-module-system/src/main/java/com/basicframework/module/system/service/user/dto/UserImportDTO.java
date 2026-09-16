package com.basicframework.module.system.service.user.dto;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.validation.EmailEx;
import com.basicframework.framework.common.validation.InEnum;
import com.basicframework.framework.common.validation.Mobile;
import com.basicframework.framework.common.validation.Nickname;
import com.basicframework.framework.common.validation.Username;
import com.basicframework.module.system.enums.common.SexEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 用户导入参数 DTO
 *
 * 承接 controller 层 UserImportExcelVO 的导入字段；约束注解与 UserSaveReqVO
 * 同名字段保持一致，保证导入逐行校验的错误文案不变
 */
@Data
public class UserImportDTO {

    /**
     * 登录名称
     */
    @NotBlank(message = "用户账号不能为空")
    @Username
    private String username;

    /**
     * 用户名称
     */
    @NotBlank(message = "用户昵称不能为空")
    @Nickname
    private String nickname;

    /**
     * 部门名称
     */
    private String deptName;

    /**
     * 用户邮箱
     */
    @EmailEx
    @Size(max = 50, message = "邮箱长度不能超过 50 个字符")
    @ToString.Exclude
    private String email;

    /**
     * 手机号码
     */
    @Mobile
    @ToString.Exclude
    private String mobile;

    /**
     * 用户性别
     */
    @InEnum(value = SexEnum.class, message = "用户性别必须是 {value}")
    private Integer sex;

    /**
     * 账号状态
     */
    @InEnum(value = CommonStatusEnum.class, message = "用户状态必须是 {value}")
    private Integer status;
}
