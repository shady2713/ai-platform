package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 服务草稿响应（协议层 VO）。 */
@Schema(description = "管理后台 - AI 服务")
@Data
@Accessors(chain = true)
public class AiServiceRespVO {

    @Schema(description = "服务编号")
    private Long id;

    @Schema(description = "所属应用编号")
    private Long appId;

    @Schema(description = "服务标识")
    private String code;

    @Schema(description = "服务名称")
    private String name;

    @Schema(description = "服务说明")
    private String description;

    @Schema(description = "状态（DRAFT/READY/ARCHIVED）")
    private String status;

    @Schema(description = "模型端点编号")
    private Long modelEndpointId;

    @Schema(description = "提示词模板")
    private String promptTemplate;

    @Schema(description = "输入 JSON Schema")
    private String inputSchema;

    @Schema(description = "输出 JSON Schema")
    private String outputSchema;

    @Schema(description = "所需能力")
    private List<String> requiredCapabilities;

    @Schema(description = "运行主体类型")
    private String runSubjectType;

    @Schema(description = "发布要求的评测得分门槛（0-100）")
    private Integer evalThreshold;

    @Schema(description = "草稿修订号")
    private Integer draftRevision;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
