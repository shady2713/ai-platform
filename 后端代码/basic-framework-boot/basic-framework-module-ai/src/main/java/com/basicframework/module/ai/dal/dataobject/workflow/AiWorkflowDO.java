package com.basicframework.module.ai.dal.dataobject.workflow;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 可视化流程定义（X08）：按应用的配置面。
 *
 * <p>标识在应用内唯一且创建后不可修改；停用（{@code DISABLED}）后不受理新运行，
 * 既有运行不受影响（它们固定在已发布的版本快照上）。
 */
@TableName("ai_workflow")
@KeySequence("ai_workflow_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiWorkflowDO extends SoftDeletableDO {

    /** 状态：启用 */
    public static final String STATUS_ENABLED = "ENABLED";

    /** 状态：停用（不受理新运行） */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 流程编号 */
    @TableId
    private Long id;

    /** 应用编号（流程属于某个 AI 应用；运行按该应用主体执行） */
    private Long applicationId;

    /** 流程标识（应用内唯一，创建后不可修改） */
    private String code;

    /** 流程名称 */
    private String name;

    /** 流程说明 */
    private String description;

    /** 状态（ENABLED/DISABLED） */
    private String status;

    /** 最新版本序号（草稿与发布共用递增） */
    private Integer latestVersionNo;

    /** 乐观锁版本 */
    private Integer version;
}
