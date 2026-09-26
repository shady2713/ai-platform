package com.basicframework.module.ai.controller.admin.observability.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 运行监控分页查询（Q03，协议层 VO）。
 *
 * <p>筛选条件都落到索引列（应用/服务/状态/主体/时间窗），由服务端过滤后再分页——
 * 客户端不做"取一页再自己筛"，否则总数与筛选结果都不成立。
 */
@Schema(description = "管理后台 - 运行监控分页")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiRunMonitorPageReqVO extends PageParam {

    @Schema(description = "应用编号", example = "1")
    private Long applicationId;

    @Schema(description = "服务编号", example = "1")
    private Long serviceId;

    @Schema(description = "运行状态（ACCEPTED/RUNNING/SUCCEEDED/FAILED/CANCELLED）")
    private String status;

    @Schema(description = "主体类型（APP/USER）")
    private String subjectType;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "起始时间（含，按运行受理时间）")
    private LocalDateTime from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "结束时间（不含，按运行受理时间）")
    private LocalDateTime to;
}
