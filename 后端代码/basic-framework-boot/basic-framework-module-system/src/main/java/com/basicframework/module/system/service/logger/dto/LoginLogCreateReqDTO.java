package com.basicframework.module.system.service.logger.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 登录日志创建参数。 */
@Data
public class LoginLogCreateReqDTO {

    @NotNull(message = "日志类型不能为空")
    private Integer logType;

    private String traceId;

    private Long userId;

    @NotNull(message = "用户类型不能为空")
    private Integer userType;

    private String username;

    @NotNull(message = "登录结果不能为空")
    private Integer result;

    @NotEmpty(message = "用户 IP 不能为空")
    private String userIp;

    private String userAgent;
}
