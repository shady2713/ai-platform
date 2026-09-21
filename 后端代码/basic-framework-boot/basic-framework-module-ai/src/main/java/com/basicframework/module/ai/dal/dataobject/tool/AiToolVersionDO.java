package com.basicframework.module.ai.dal.dataobject.tool;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 工具版本（D08）：政策 + 输入输出 schema + 来源绑定的不可变快照。
 *
 * <p>发布后不可修改：改政策/schema 必须新建版本——"政策被悄悄放宽"因此在数据层不可表达。
 */
@TableName("ai_tool_version")
@KeySequence("ai_tool_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiToolVersionDO extends SoftDeletableDO {

    /** 状态：草稿 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：已发布（不可变） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 来源类型：HTTP operation（首期唯一支持的来源） */
    public static final String SOURCE_HTTP_OPERATION = "HTTP_OPERATION";

    /** 版本编号 */
    @TableId
    private Long id;

    /** 工具编号 */
    private Long toolId;

    /** 版本号（工具内递增，发布后不可变） */
    private Integer versionNo;

    /** 状态（DRAFT/PUBLISHED） */
    private String status;

    /** 类型（READ/WRITE）；首期只允许发布 READ */
    private String toolType;

    /** 执行政策（AUTO/CONFIRM/DENY），默认 DENY */
    private String policy;

    /** 来源类型（HTTP_OPERATION） */
    private String sourceKind;

    /** 来源标识（operationKey） */
    private String sourceRef;

    /** 输入 schema（声明参数名/类型/必填） */
    private String inputSchemaJson;

    /** 输出 schema（结果列声明） */
    private String outputSchemaJson;

    /** 版本内容哈希（政策 + schema + 来源） */
    private String schemaHash;

    /** 发布时间 */
    private java.time.LocalDateTime publishedAt;

    /** 乐观锁版本 */
    private Integer version;
}
