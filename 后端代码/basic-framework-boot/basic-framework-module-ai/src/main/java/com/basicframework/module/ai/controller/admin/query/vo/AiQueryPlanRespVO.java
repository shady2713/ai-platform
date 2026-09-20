package com.basicframework.module.ai.controller.admin.query.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 查询规划结果（协议层 VO）：PLAN 与 CLARIFICATION 两种正常结果。
 *
 * <p>只回逻辑码、计划哈希与澄清候选；不含上游数据行、物理表名、权限码与凭据。
 */
@Schema(description = "管理后台 - AI 查询规划结果")
@Data
@Accessors(chain = true)
public class AiQueryPlanRespVO {

    @Schema(description = "结果类型（PLAN/CLARIFICATION）")
    private String kind;

    @Schema(description = "数据集编号")
    private Long datasetId;

    @Schema(description = "数据集标识")
    private String datasetCode;

    @Schema(description = "计划中的数据集标识")
    private String planDatasetId;

    @Schema(description = "数据集版本编号")
    private Long datasetVersionId;

    @Schema(description = "语义版本号")
    private Integer datasetVersionNo;

    @Schema(description = "定义内容哈希")
    private String schemaHash;

    @Schema(description = "计划内容哈希")
    private String planHash;

    @Schema(description = "已校验计划（规范化 JSON；PLAN 时存在）")
    private String planJson;

    @Schema(description = "澄清追问（CLARIFICATION 时存在）")
    private String question;

    @Schema(description = "澄清原因（AMBIGUOUS/UNSUPPORTED/OUT_OF_SCOPE）")
    private String reason;

    @Schema(description = "澄清候选（只来自本次授权目录）")
    private List<Candidate> candidates;

    @Schema(description = "实际模型输出次数")
    private Integer attempts;

    @Schema(description = "澄清候选")
    @Data
    public static class Candidate {

        @Schema(description = "逻辑码")
        private String code;

        @Schema(description = "展示名")
        private String label;
    }
}
