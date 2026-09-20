package com.basicframework.module.ai.service.query.planner.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 查询规划结果（服务层 DTO）：PLAN 与 CLARIFICATION 是**两种正常结果**。
 *
 * <p>PLAN 带已校验计划（规范化 JSON + 计划哈希 + 数据集版本锚点）；CLARIFICATION 带追问与有限候选。
 * 两者都只含逻辑码与结论，不含上游数据行、物理表名与凭据。
 */
@Data
@Accessors(chain = true)
public class AiQueryPlanResultDTO {

    /** 结果类型（PLAN/CLARIFICATION） */
    private String kind;

    /** 数据集编号 */
    private Long datasetId;

    /** 数据集标识 */
    private String datasetCode;

    /** 计划中的数据集标识（dset_ 前缀） */
    private String planDatasetId;

    /** 数据集版本编号 */
    private Long datasetVersionId;

    /** 语义版本号 */
    private Integer datasetVersionNo;

    /** 定义内容哈希 */
    private String schemaHash;

    /** 计划内容哈希（同一份计划永远同一哈希） */
    private String planHash;

    /** 已校验计划（规范化 JSON 文本；PLAN 时存在） */
    private String planJson;

    /** 澄清追问（CLARIFICATION 时存在） */
    private String question;

    /** 澄清原因（AMBIGUOUS/UNSUPPORTED/OUT_OF_SCOPE） */
    private String reason;

    /** 澄清候选（只来自本次授权目录） */
    private List<Candidate> candidates;

    /** 实际使用的模型输出次数（1 表示一次通过） */
    private Integer attempts;

    /** 澄清候选：逻辑码 + 展示名。 */
    public record Candidate(String code, String label) {}
}
