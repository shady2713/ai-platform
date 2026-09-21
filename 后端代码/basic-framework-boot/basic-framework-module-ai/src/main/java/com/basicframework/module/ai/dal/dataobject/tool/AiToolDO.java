package com.basicframework.module.ai.dal.dataobject.tool;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 工具注册（D08）：工具标识 + 来源连接器 + 版本序列。
 *
 * <p>工具本身只是"身份与来源"，执行政策与输入输出 schema 都在版本里（不可变快照）。
 */
@TableName("ai_tool")
@KeySequence("ai_tool_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiToolDO extends SoftDeletableDO {

    /** 状态：启用 */
    public static final String STATUS_ENABLED = "ENABLED";

    /** 状态：停用 */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 工具编号 */
    @TableId
    private Long id;

    /** 工具标识（全局唯一，创建后不可修改） */
    private String code;

    /** 工具名称 */
    private String name;

    /** 说明（供模型理解用途） */
    private String description;

    /** 连接器编号（工具来源） */
    private Long connectorId;

    /** 状态（ENABLED/DISABLED） */
    private String status;

    /** 最新版本号（0 表示尚无版本） */
    private Integer latestVersionNo;

    /** 乐观锁版本 */
    private Integer version;
}
