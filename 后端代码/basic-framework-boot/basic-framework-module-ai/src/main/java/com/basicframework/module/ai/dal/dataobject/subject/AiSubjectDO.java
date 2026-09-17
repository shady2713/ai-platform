package com.basicframework.module.ai.dal.dataobject.subject;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 外部主体（A02）。
 *
 * <p>唯一键 {@code (applicationId, subjectType, externalUserId)}：同一外部用户名在不同应用下互不冲突。
 * 中台只保存身份事实与范围来源/版本，不保存外部凭据，也不保存 roles/deptIds。
 */
@TableName("ai_subject")
@KeySequence("ai_subject_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiSubjectDO extends SoftDeletableDO {

    /** 状态：可用 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已停用/已撤销（业务侧撤销同步到此） */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 主体编号 */
    @TableId
    private Long id;

    /** 所属应用编号 */
    private Long applicationId;

    /** 主体类型（APP/USER） */
    private String subjectType;

    /** 可信外部用户标识（APP 主体为空串） */
    private String externalUserId;

    /** 外部主体显示名（仅展示） */
    private String displayName;

    /** 状态（ACTIVE/DISABLED） */
    private String status;

    /** 外部授权来源标识 */
    private String scopeSource;

    /** 当前范围版本（范围变化必须递增） */
    private Long scopeVersion;

    /** 乐观锁版本 */
    private Integer version;
}
