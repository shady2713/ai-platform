package com.basicframework.module.ai.service.knowledge.ingestion;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 知识文档入库任务（K03）：租约 + 栅栏 + 重试上限。
 *
 * <p>为什么任务表跟文档版本在同一事务里创建：入库的可见性由"版本 + 任务"共同决定。
 * 只写版本不写任务会出现"版本已可见但永远不会被处理"的悬空状态；只写任务不写版本则是无主任务。
 * 两者同事务，失败整笔回滚，文件由上传方补偿解除引用（A07 的释放语义）。
 *
 * <p>租约字段语义与 O03 的 `ai_run_task` 一致：领取是 CAS（QUEUED → RUNNING 且写 owner/epoch/到期时间），
 * 续租与落库都必须带 (owner, epoch)，租约过期被接管后旧 worker 命中 0 行。
 */
@TableName("ai_knowledge_ingestion_task")
@KeySequence("ai_knowledge_ingestion_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiKnowledgeIngestionTaskDO extends SoftDeletableDO {

    /** 状态：待领取。 */
    public static final String STATUS_QUEUED = "QUEUED";

    /** 状态：执行中（持有租约）。 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：成功。 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 状态：失败（达到重试上限或不可重试错误）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：结果未知（进程中断且无法判定，需人工重试）。 */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 任务类型：解析。 */
    public static final String KIND_PARSE = "PARSE";

    /** 任务类型：切分与向量化。 */
    public static final String KIND_INDEX = "INDEX";

    /** 任务编号 */
    @TableId
    private Long id;

    /** 知识库编号 */
    private Long knowledgeBaseId;

    /** 文档编号 */
    private Long documentId;

    /** 文档版本编号 */
    private Long documentVersionId;

    /** 任务类型（PARSE/INDEX） */
    private String taskKind;

    /** 状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN） */
    private String status;

    /** 已尝试次数 */
    private Integer attemptCount;

    /** 最大尝试次数 */
    private Integer maxAttempts;

    /** 下次可领取时间 */
    private java.time.LocalDateTime nextAttemptTime;

    /** 租约持有者 */
    private String leaseOwner;

    /** 租约到期时间 */
    private java.time.LocalDateTime leaseExpiresTime;

    /** 最近续租时间 */
    private java.time.LocalDateTime heartbeatTime;

    /** 领取代数（栅栏） */
    private Integer claimedEpoch;

    /** 最近失败原因（脱敏稳定原因码） */
    private String lastErrorCode;

    /** 乐观锁版本 */
    private Integer version;
}
