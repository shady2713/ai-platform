package com.basicframework.module.ai.service.subject.dto;

import java.util.Set;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 主体范围解析结果（服务层 DTO）：
 * {@code denied=true} 表示按 DENY 处理（解析器失败、空集合、超预算或主体不可用），消费方必须拒绝执行。
 */
@Data
@Accessors(chain = true)
public class AiSubjectScopeDTO {

    /** 所属应用编号 */
    private Long applicationId;

    /** 主体类型 */
    private String subjectType;

    /** 可信外部用户标识 */
    private String externalUserId;

    /** 是否拒绝（true 时不得执行任何数据访问） */
    private boolean denied;

    /** 拒绝原因码（稳定词表：SUBJECT_NOT_FOUND/SUBJECT_DISABLED/RESOLVER_UNAVAILABLE/RESOLVER_FAILED/EMPTY_SCOPE/SCOPE_OVER_BUDGET） */
    private String denyReason;

    /** 组织白名单 */
    private Set<Long> organizationIds;

    /** 对象白名单 */
    private Set<String> resourceKeys;

    /** 外部授权来源 */
    private String scopeSource;

    /** 范围版本 */
    private long scopeVersion;
}
