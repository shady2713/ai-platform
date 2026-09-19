package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 评测结论（协议层 VO）：passed 由平台按冻结门槛判定。 */
@Schema(description = "管理后台 - AI 服务发布评测结论")
@Data
@Accessors(chain = true)
public class AiServiceEvaluationRespVO {

    @Schema(description = "评测记录编号")
    private Long id;

    @Schema(description = "发布版本编号")
    private Long releaseId;

    @Schema(description = "被评测内容摘要")
    private String contentHash;

    @Schema(description = "评测所用端点配置版本")
    private Integer endpointConfigRevision;

    @Schema(description = "评测得分")
    private Integer score;

    @Schema(description = "评测门槛")
    private Integer threshold;

    @Schema(description = "是否通过")
    private Boolean passed;

    @Schema(description = "评测用例数")
    private Integer caseCount;

    @Schema(description = "备注")
    private String notes;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
