package com.basicframework.module.ai.dal.dataobject.workflow;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 流程版本（X08）：不可变的图快照。
 *
 * <p>只有 {@code DRAFT} 可编辑；发布用乐观锁 CAS 落 {@code PUBLISHED} 并冻结
 * {@link #graphHash}——已发布版本不接受任何修改（"编辑草稿"永远是新建版本），
 * 运行受理固定到版本编号，因此草稿/新版本的任何变更都不影响已受理运行的语义。
 * 同一流程同时最多一个打开的草稿（函数唯一键兜底，废弃置 {@code DISCARDED} 释放）。
 */
@TableName("ai_workflow_version")
@KeySequence("ai_workflow_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiWorkflowVersionDO extends SoftDeletableDO {

    /** 状态：草稿（可编辑；同一流程最多一个） */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：已发布（不可变快照） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 状态：已废弃（草稿被丢弃；释放"单开草稿"键） */
    public static final String STATUS_DISCARDED = "DISCARDED";

    /** 流程版本编号 */
    @TableId
    private Long id;

    /** 流程编号 */
    private Long workflowId;

    /** 版本序号（同一流程内递增，发布后不可变） */
    private Integer versionNo;

    /** 状态（DRAFT/PUBLISHED/DISCARDED） */
    private String status;

    /** 流程图 JSON（nodes/edges 受控契约） */
    private String graphJson;

    /** 图内容摘要（sha-256，发布时冻结） */
    private String graphHash;

    /** 节点数（发布期有界） */
    private Integer nodeCount;

    /** 边数（发布期有界） */
    private Integer edgeCount;

    /** 发布时间 */
    private LocalDateTime publishedAt;

    /** 乐观锁版本 */
    private Integer version;
}
