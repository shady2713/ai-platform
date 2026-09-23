package com.basicframework.module.ai.dal.dataobject.report;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 报表（R04）：可刷新定义 + 私有归属 + 模式。
 *
 * <p>归属（应用 + 主体类型 + 外部用户标识）来自服务端会话身份：首期是**私人报表**，
 * 跨用户复制报表编号、旧版本编号或走预览入口都不能绕过归属判定（第 5 步）。
 */
@TableName("ai_report")
@KeySequence("ai_report_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiReportDO extends SoftDeletableDO {

    /** 模式：快照（数据冻结在保存时刻）。 */
    public static final String MODE_SNAPSHOT = "SNAPSHOT";

    /** 模式：可刷新（按当前权限重新执行原配置）。 */
    public static final String MODE_REFRESHABLE = "REFRESHABLE";

    /** 报表编号 */
    @TableId
    private Long id;

    /** 报表标识（应用内唯一，创建后不可修改） */
    private String code;

    /** 报表名称 */
    private String name;

    /** 说明 */
    private String description;

    /** 所属应用编号 */
    private Long applicationId;

    /** 主体类型（APP/USER） */
    private String subjectType;

    /** 外部用户标识（所有者） */
    private String externalUserId;

    /** 模式（SNAPSHOT/REFRESHABLE） */
    private String mode;

    /** 来源服务编号 */
    private Long serviceId;

    /** 来源服务发布版本编号 */
    private Long releaseId;

    /** 主题标识（保存时的主题） */
    private String themeId;

    /** 主题修订号 */
    private Integer themeRevision;

    /** ReportSpec 契约版本（旧版本加载时判定兼容性，AT-049） */
    private String schemaVersion;

    /** 最新版本号 */
    private Integer latestVersionNo;

    /** 当前生效版本号 */
    private Integer publishedVersionNo;

    /** 乐观锁版本 */
    private Integer version;
}
