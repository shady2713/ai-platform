package com.basicframework.module.ai.controller.admin.usage.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 用量账本分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 用量账本分页")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiUsagePageReqVO extends PageParam {

    @Schema(description = "应用编号", example = "1")
    private Long applicationId;

    @Schema(description = "起始时间（含）")
    private LocalDateTime from;

    @Schema(description = "服务编号", example = "1")
    private Long serviceId;

    @Schema(description = "结束时间（含）")
    private LocalDateTime to;
}
