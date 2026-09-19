package com.basicframework.module.ai.dal.dataobject.event;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 运行事件（O05）：SSE 事件源，序号由运行行并发安全分配，事件与运行状态同事务提交。
 *
 * <p>{@code blockJson} 只保存受控结果块，不保存提示词、模型输入正文或凭据；
 * 心跳不是事件（它是 SSE 注释），因此不会出现在本表里，也不会推进序号。
 */
@TableName("ai_run_event")
@KeySequence("ai_run_event_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiRunEventDO extends SoftDeletableDO {

    /** 事件契约版本（RunEvent v1） */
    public static final String SCHEMA_VERSION = "1.0";

    /** 事件编号 */
    @TableId
    private Long id;

    /** 运行编号 */
    private Long runId;

    /** 运行内事件序号（从 1 递增） */
    private Integer seq;

    /** 事件状态 */
    private String status;

    /** 结果块类型（受控结构；无块时为空） */
    private String blockType;

    /** 结果块正文（受控结构；不进日志） */
    @ToString.Exclude
    private String blockJson;

    /** 事件契约版本 */
    private String schemaVersion;

    /** 乐观锁版本 */
    private Integer version;
}
