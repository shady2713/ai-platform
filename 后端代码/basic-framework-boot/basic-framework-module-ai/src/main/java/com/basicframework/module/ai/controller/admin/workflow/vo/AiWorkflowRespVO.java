package com.basicframework.module.ai.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** AI 流程定义响应（协议层 VO）。 */
@Schema(description = "管理后台 - AI 流程定义 Response VO")
@Data
@Accessors(chain = true)
public class AiWorkflowRespVO {

    @Schema(description = "流程编号")
    private Long id;

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "流程标识")
    private String code;

    @Schema(description = "流程名称")
    private String name;

    @Schema(description = "流程说明")
    private String description;

    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;

    @Schema(description = "最新版本序号")
    private Integer latestVersionNo;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
