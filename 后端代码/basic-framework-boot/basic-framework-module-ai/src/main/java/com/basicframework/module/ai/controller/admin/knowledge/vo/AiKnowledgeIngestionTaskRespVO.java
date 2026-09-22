package com.basicframework.module.ai.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 入库任务响应（协议层 VO）：只含状态与脱敏原因码，不含解析异常正文。 */
@Schema(description = "管理后台 - AI 知识入库任务")
@Data
@Accessors(chain = true)
public class AiKnowledgeIngestionTaskRespVO {

    @Schema(description = "任务编号")
    private Long id;

    @Schema(description = "知识库编号")
    private Long knowledgeBaseId;

    @Schema(description = "文档编号")
    private Long documentId;

    @Schema(description = "文档版本编号")
    private Long documentVersionId;

    @Schema(description = "任务类型（PARSE/INDEX）")
    private String taskKind;

    @Schema(description = "状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN）")
    private String status;

    @Schema(description = "已尝试次数")
    private Integer attemptCount;

    @Schema(description = "最大尝试次数")
    private Integer maxAttempts;

    @Schema(description = "下次可领取时间")
    private java.time.LocalDateTime nextAttemptTime;

    @Schema(description = "最近失败原因（脱敏原因码）")
    private String lastErrorCode;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private java.time.LocalDateTime createTime;
}
