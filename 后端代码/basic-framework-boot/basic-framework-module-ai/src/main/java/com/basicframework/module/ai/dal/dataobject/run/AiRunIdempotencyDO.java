package com.basicframework.module.ai.dal.dataobject.run;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 运行幂等记录（O02）：(应用, 主体类型, 外部用户标识, 幂等键) 唯一。
 *
 * <p>并发受理由该唯一键兜底：插入冲突时回读原记录比较 {@code requestDigest}，
 * 摘要相同返回原 run（不重新发起模型调用），摘要不同返回 409。
 */
@TableName("ai_run_idempotency")
@KeySequence("ai_run_idempotency_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiRunIdempotencyDO extends SoftDeletableDO {

    /** 幂等记录编号 */
    @TableId
    private Long id;

    /** 应用编号 */
    private Long applicationId;

    /** 主体类型 */
    private String subjectType;

    /** 可信外部用户标识 */
    private String externalUserId;

    /** 幂等键（调用方提供） */
    private String idempotencyKey;

    /** 请求摘要 */
    private String requestDigest;

    /** 首次受理产生的运行编号 */
    private Long runId;

    /** 乐观锁版本 */
    private Integer version;
}
