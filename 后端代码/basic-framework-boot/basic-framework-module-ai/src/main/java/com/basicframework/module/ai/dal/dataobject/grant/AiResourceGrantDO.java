package com.basicframework.module.ai.dal.dataobject.grant;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 资源授权（A03）：主体对某类某资源的动作白名单。
 *
 * <p>唯一键含主体身份与资源类型：同一 application 下的不同主体、或不同类型资源的相同标识互不影响。
 */
@TableName("ai_resource_grant")
@KeySequence("ai_resource_grant_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiResourceGrantDO extends SoftDeletableDO {

    /** 状态：有效 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已撤销 */
    public static final String STATUS_REVOKED = "REVOKED";

    /** 授权编号 */
    @TableId
    private Long id;

    /** 应用编号 */
    private Long applicationId;

    /** 主体类型（APP/USER） */
    private String subjectType;

    /** 外部用户标识（APP 主体为空串） */
    private String externalUserId;

    /** 资源类型（REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET） */
    private String resourceType;

    /** 资源标识 */
    private String resourceKey;

    /** 动作白名单（逗号分隔：READ,EXECUTE,EXPORT） */
    private String actions;

    /** 状态（ACTIVE/REVOKED） */
    private String status;

    /** 授权版本：授权或撤销时递增 */
    private Long authzRevision;

    /** 乐观锁版本 */
    private Integer version;
}
