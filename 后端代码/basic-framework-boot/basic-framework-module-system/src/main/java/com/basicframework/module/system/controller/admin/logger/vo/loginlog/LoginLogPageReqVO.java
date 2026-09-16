package com.basicframework.module.system.controller.admin.logger.vo.loginlog;

import static com.basicframework.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

@Schema(description = "管理后台 - 登录日志分页列表 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class LoginLogPageReqVO extends PageParam {

    @Schema(description = "用户 IP，模糊匹配", example = "127.0.0.1")
    @Size(max = 50, message = "用户 IP 长度不能超过 50 个字符")
    private String userIp;

    @Schema(description = "用户账号，模糊匹配", example = "admin")
    @Size(max = 50, message = "用户账号长度不能超过 50 个字符")
    private String username;

    @Schema(description = "操作状态", example = "true")
    private Boolean status;

    @Schema(description = "登录时间", example = "[2022-07-01 00:00:00,2022-07-01 23:59:59]")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Size(min = 2, max = 2, message = "登录时间必须包含开始和结束时间")
    private LocalDateTime[] createTime;
}
