package com.basicframework.module.ai.service.report.revision.dto;

import com.basicframework.module.ai.service.report.revision.AiReportRevisionDiff;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表对话修改结果（服务层 DTO）：已应用的新版本，或"需要澄清"的追问。
 *
 * <p>两种结果都是**正常结果**（与 D05 一致）：澄清不是失败，而是问题有歧义时先问清楚，
 * 不替用户猜一个口径去查数据、更不会因此改写报表。
 */
@Data
@Accessors(chain = true)
public class AiReportRevisionResultDTO {

    /** 结果：已应用（产生新版本）。 */
    public static final String OUTCOME_APPLIED = "APPLIED";

    /** 结果：需要澄清（未创建版本）。 */
    public static final String OUTCOME_CLARIFICATION = "CLARIFICATION";

    /** 结果类型（APPLIED/CLARIFICATION） */
    private String outcome;

    /** 报表编号 */
    private Long reportId;

    /** 基础版本号 */
    private Integer baseVersionNo;

    /** 新版本号（APPLIED 时存在；原版本保持不变） */
    private Integer newVersionNo;

    /** 本次是否执行了受控查询（展示类修订为 false：换图不重复查库） */
    private boolean queryPerformed;

    /** 结构性差异（改了什么） */
    private AiReportRevisionDiff diff;

    /** 澄清追问（CLARIFICATION 时存在） */
    private String clarificationQuestion;

    /** 澄清原因（AMBIGUOUS/UNSUPPORTED/OUT_OF_SCOPE） */
    private String clarificationReason;

    /** 澄清候选（只来自本次授权目录） */
    private List<Candidate> clarificationCandidates;

    /** 说明（空数据、修复等） */
    private List<String> notes;

    /** 澄清候选：逻辑码 + 展示名。 */
    public record Candidate(String code, String label) {}
}
