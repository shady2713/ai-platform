package com.basicframework.module.ai.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** AI 流程版本响应（协议层 VO）：graphJson 只对 DRAFT 可写；已发布版本它是不可变快照的只读内容。 */
@Schema(description = "管理后台 - AI 流程版本 Response VO")
@Data
@Accessors(chain = true)
public class AiWorkflowVersionRespVO {

    @Schema(description = "版本编号")
    private Long id;

    @Schema(description = "流程编号")
    private Long workflowId;

    @Schema(description = "版本序号")
    private Integer versionNo;

    @Schema(description = "状态（DRAFT/PUBLISHED/DISCARDED）")
    private String status;

    @Schema(description = "流程图 JSON（nodes/edges 受控契约）")
    private String graphJson;

    @Schema(description = "图内容摘要（发布时冻结）")
    private String graphHash;

    @Schema(description = "节点数")
    private Integer nodeCount;

    @Schema(description = "边数")
    private Integer edgeCount;

    @Schema(description = "发布时间")
    private LocalDateTime publishedAt;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
