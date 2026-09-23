package com.basicframework.module.ai.service.run.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 运行侧受控查询结果（R05）：已校验计划 + 真实执行结果（或澄清）。
 *
 * <p>PLAN 与 CLARIFICATION 是两种**正常结果**（与 D05 一致）：澄清不是失败，
 * 而是"问题有歧义，需要用户先选一个"——调用方据此把追问带回对话，而不是猜一个口径去查。
 *
 * <p>行数据不进 {@code toString()}（避免把业务数据带进日志）；凭据、连接串与物理表名都不在结果里。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"rows"})
public class AiRunQueryExecutionResultDTO {

    /** 结果类型：已执行计划。 */
    public static final String KIND_PLAN = "PLAN";

    /** 结果类型：需要澄清。 */
    public static final String KIND_CLARIFICATION = "CLARIFICATION";

    /** 完整性：取完且未触达任何上限。 */
    public static final String COMPLETE = "COMPLETE";

    /** 完整性：被行数上限截断（不得当作完整统计）。 */
    public static final String PARTIAL = "PARTIAL";

    /** 结果类型（PLAN/CLARIFICATION） */
    private String kind;

    /** 数据集编号 */
    private Long datasetId;

    /** 数据集标识 */
    private String datasetCode;

    /** 数据集版本编号 */
    private Long datasetVersionId;

    /** 语义版本号 */
    private Integer datasetVersionNo;

    /** 定义内容哈希 */
    private String schemaHash;

    /** 计划内容哈希（同一份计划永远同一哈希） */
    private String planHash;

    /** 已校验计划（规范化 JSON 文本） */
    private String planJson;

    /** 结果标识（由计划哈希派生，稳定且不含数据） */
    private String resultRef;

    /** 结果列（逻辑码 + 展示标签 + 语义类型） */
    private List<Column> columns;

    /** 结果行（按逻辑码取值） */
    private List<Map<String, Object>> rows;

    /** 返回行数 */
    private int rowCount;

    /** 数据完整性（COMPLETE/PARTIAL） */
    private String completeness;

    /** 是否被行数上限截断 */
    private boolean truncated;

    /** 澄清追问（CLARIFICATION 时存在） */
    private String clarificationQuestion;

    /** 澄清原因（AMBIGUOUS/UNSUPPORTED/OUT_OF_SCOPE） */
    private String clarificationReason;

    /** 澄清候选（只来自本次授权目录） */
    private List<Candidate> clarificationCandidates;

    /** 结果列：逻辑码 + 标签 + 语义类型 + 单位（单位来自语义定义，报表直接用它标注）。 */
    public record Column(String code, String label, String type, String unit) {}

    /** 澄清候选：逻辑码 + 展示名。 */
    public record Candidate(String code, String label) {}

    /** 数据集在 A03 资源目录里的键（{@code dset_} + 数据集标识），与计划里的 datasetId 同形。 */
    public String datasetResourceKey() {
        return datasetCode == null ? null : "dset_" + datasetCode;
    }
}
