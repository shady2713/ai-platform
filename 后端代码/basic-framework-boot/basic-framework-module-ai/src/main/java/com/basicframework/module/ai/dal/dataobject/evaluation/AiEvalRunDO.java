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
 * AI 评测运行（Q04）：**事实**，只追加。
 *
 * <p>执行时冻结套件：{@link #suiteRevision} 与 {@link #suiteDigest} 是运行自己的事实，
 * 之后套件怎么改都不影响本行；{@link #summaryJson} 保存逐例（caseKey + 摘要 + 级别 + 是否需复核），
 * 因此可复现报告只需要本表 + 结果表，不依赖当前配置。
 */
@TableName("ai_eval_run")
@KeySequence("ai_eval_run_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiEvalRunDO extends BaseDO {

    /** 状态：执行中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：已完成（可含失败/错误样例） */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 状态：执行失败（整体未能完成，如套件不可执行） */
    public static final String STATUS_FAILED = "FAILED";

    /** 评测运行编号 */
    @TableId
    private Long id;

    /** 套件编号 */
    private Long suiteId;

    /** 应用编号（快照） */
    private Long applicationId;

    /** 服务编号（快照） */
    private Long serviceId;

    /** 执行时的套件修订号 */
    private Integer suiteRevision;

    /** 执行时的套件内容摘要 */
    private String suiteDigest;

    /** 状态（RUNNING/COMPLETED/FAILED） */
    private String status;

    /** 样例总数 */
    private Integer caseTotal;

    /** 通过数 */
    private Integer passedCount;

    /** 失败数 */
    private Integer failedCount;

    /** 错误数（未能执行） */
    private Integer errorCount;

    /** 逐例快照摘要（执行即冻结） */
    @ToString.Exclude
    private String summaryJson;

    /** 开始时间 */
    private LocalDateTime startedTime;

    /** 结束时间 */
    private LocalDateTime finishedTime;
}
