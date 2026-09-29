package com.basicframework.module.ai.service.application.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 授权目录中的一个业务系统条目（Y01）。
 *
 * <p>业务系统 = 接入应用：{@link #appCode} 是稳定且不可修改的系统标识。条目只在**当前主体确实
 * 有可访问范围**时出现（主体 ACTIVE + 范围解析非空 + 至少一条 ACTIVE 授权 + 已批准联邦映射），
 * 因此"无权系统"不是被标注为无权，而是**不存在于结果**。
 */
@Data
@Accessors(chain = true)
public class AiSystemEntryDTO {

    /** 系统（应用）编号 */
    private Long applicationId;

    /** 系统标识（应用 appCode，稳定且不可修改） */
    private String appCode;

    /** 系统名称（应用名称，仅展示） */
    private String systemName;

    /** 是否为当前会话所在系统（跨系统分析必须包含它） */
    private boolean currentSystem;

    /** 联邦映射编号（当前系统为 null） */
    private Long federationId;

    /** 联邦映射版本（批准/撤销递增；当前系统为 null） */
    private Long federationRevision;

    /** 主体在该系统中的类型 */
    private String subjectType;

    /** 主体在该系统中的外部用户标识（服务端登记事实，不按同名推断） */
    private String externalUserId;

    /** 范围来源标识（A02） */
    private String scopeSource;

    /** 范围版本（A02） */
    private Long scopeVersion;

    /** 该系统内的可访问范围（按资源类型 + 资源标识排序） */
    private List<AiSystemScopeDTO> scopes;

    /** 系统访问指纹：身份 + 范围 + 映射版本的稳定摘要，范围选择据此判定事实是否变化 */
    private String systemFingerprint;
}
