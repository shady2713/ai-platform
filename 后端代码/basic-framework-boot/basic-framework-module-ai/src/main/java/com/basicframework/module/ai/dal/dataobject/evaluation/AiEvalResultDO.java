package com.basicframework.module.ai.dal.dataobject.evaluation;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 评测结果（Q04）：**事实**，只追加。
 *
 * <p>逐例保存：判定（{@link #status}）、逐条核对结论（{@link #verdictJson}：规则/期望/实际/说明）、
 * 样例摘要（{@link #caseDigest}）与结果摘要（{@link #resultDigest}）。
 * 结论里只有稳定值与摘要，没有提示词与响应正文（AT-011/058 口径）；
 * 需要人工复核的样例先落 {@code REVIEW_REQUIRED}，复核后写 {@link #reviewStatus}。
 */
@TableName("ai_eval_result")
@KeySequence("ai_eval_result_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiEvalResultDO extends BaseDO {

    /** 判定：通过 */
    public static final String STATUS_PASSED = "PASSED";

    /** 判定：不符合期望（规则程序化核验后给出） */
    public static final String STATUS_FAILED = "FAILED";

    /** 判定：未能执行（上游不可用等，附稳定错误码） */
    public static final String STATUS_ERROR = "ERROR";

    /** 判定：需要人工复核（规则通过但样例标记 needsReview） */
    public static final String STATUS_REVIEW_REQUIRED = "REVIEW_REQUIRED";

    /** 复核状态：不需要 */
    public static final String REVIEW_NOT_REQUIRED = "NOT_REQUIRED";

    /** 复核状态：待复核 */
    public static final String REVIEW_PENDING = "PENDING";

    /** 复核状态：人工通过 */
    public static final String REVIEW_APPROVED = "APPROVED";

    /** 复核状态：人工否决 */
    public static final String REVIEW_REJECTED = "REJECTED";

    /** 结果编号 */
    @TableId
    private Long id;

    /** 评测运行编号 */
    private Long runId;

    /** 样例编号（样例删除后仍保留快照字段） */
    private Long caseId;

    /** 样例标识（快照） */
    private String caseKey;

    /** 严重级别（快照） */
    private String severity;

    /** 判定（PASSED/FAILED/ERROR/REVIEW_REQUIRED） */
    private String status;

    /** 期望版本标识（快照） */
    private String expectVersion;

    /** 实际版本标识（如模型端点修订） */
    private String observedVersion;

    /** 本次评测用例产生的运行编号（走同一运行服务时给出） */
    private Long runRef;

    /** 逐条核对结论（规则/期望/实际/说明） */
    @ToString.Exclude
    private String verdictJson;

    /** 错误码（未能执行时给出稳定码） */
    private String failureCode;

    /** 冻结的样例摘要（同摘要同期望） */
    private String caseDigest;

    /** 结果摘要（样例摘要 + 实际事实 + 结论） */
    private String resultDigest;

    /** 人工复核状态（NOT_REQUIRED/PENDING/APPROVED/REJECTED） */
    private String reviewStatus;

    /** 复核备注 */
    private String reviewNote;

    /** 复核人 */
    private String reviewedBy;

    /** 复核时间 */
    private LocalDateTime reviewedTime;
}
