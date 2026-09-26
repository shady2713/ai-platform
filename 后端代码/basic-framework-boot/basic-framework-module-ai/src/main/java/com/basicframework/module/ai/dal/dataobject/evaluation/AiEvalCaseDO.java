package com.basicframework.module.ai.dal.dataobject.evaluation;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 评测样例（Q04）：**配置**，一行 = 套件里的一个合成样例。
 *
 * <p>样例只保存三件事实：合成问题（{@link #question}）、期望规则（{@link #checksJson}，
 * 确定性核对词表，见 docs/security/ai-eval-fixtures.md）与期望版本标识（{@link #expectVersion}）。
 * 判定不靠模型打分：规则由 {@code AiEvalChecks} 程序化执行（金额/日期/引用/结构）。
 */
@TableName("ai_eval_case")
@KeySequence("ai_eval_case_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiEvalCaseDO extends SoftDeletableDO {

    /** 严重级别：阻断（失败即视为套件失败） */
    public static final String SEVERITY_BLOCKER = "BLOCKER";

    /** 严重级别：主要 */
    public static final String SEVERITY_MAJOR = "MAJOR";

    /** 严重级别：次要 */
    public static final String SEVERITY_MINOR = "MINOR";

    /** 样例编号 */
    @TableId
    private Long id;

    /** 所属套件编号 */
    private Long suiteId;

    /** 样例标识（套件内唯一） */
    private String caseKey;

    /** 标题 */
    private String title;

    /** 严重级别（BLOCKER/MAJOR/MINOR） */
    private String severity;

    /** 合成问题（禁止真实客户数据） */
    private String question;

    /** 期望的模型/服务版本标识（可空：不校验版本时留空） */
    private String expectVersion;

    /** 期望规则（确定性核对词表的 JSON 数组） */
    @ToString.Exclude
    private String checksJson;

    /** 是否需要人工复核 */
    private Boolean needsReview;

    /** 乐观锁版本 */
    private Integer version;
}
