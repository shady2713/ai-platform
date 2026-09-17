package com.basicframework.module.infra.api.file.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 文件操作主体：必须由调用方从已认证会话构建，不得由请求参数或客户端 context 直接构造。
 *
 * <p>跨模块调用沿用平台的"令牌 → 会话"身份来源约束（见 ADR 0049），
 * infra 只按传入主体做授权判定，不自行猜测调用者身份。
 */
@Data
public class FileSubjectDTO {

    /** 用户类型，取值见 UserTypeEnum */
    @NotNull(message = "用户类型不能为空")
    private Integer userType;

    /** 用户编号（MEMBER 类型时为 AI 主体编号） */
    @NotNull(message = "用户编号不能为空")
    private Long userId;
}
