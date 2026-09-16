package com.basicframework.module.system.controller.admin.logger.vo.operatelog;

import static com.basicframework.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

@Schema(description = "管理后台 - 操作日志分页列表 Request VO")
@Data
public class OperateLogPageReqVO extends PageParam {

    @Schema(description = "用户编号", example = "1")
    @Positive(message = "用户编号必须为正数")
    private Long userId;

    @Schema(description = "操作模块业务编号", example = "1")
    @Positive(message = "业务编号必须为正数")
    private Long bizId;

    @Schema(description = "操作模块，模糊匹配", example = "订单")
    @Size(max = 50, message = "操作模块长度不能超过 50 个字符")
    private String type;

    @Schema(description = "操作名，模糊匹配", example = "创建订单")
    @Size(max = 50, message = "操作名长度不能超过 50 个字符")
    private String subType;

    @Schema(description = "操作明细，模糊匹配", example = "修改编号为 1 的用户信息")
    @Size(max = 2000, message = "操作明细长度不能超过 2000 个字符")
    private String action;

    @Schema(description = "开始时间", example = "[2022-07-01 00:00:00,2022-07-01 23:59:59]")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Size(min = 2, max = 2, message = "开始时间必须包含起止两个端点")
    private LocalDateTime[] createTime;
}
