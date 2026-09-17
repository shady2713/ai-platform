package com.basicframework.module.ai.domain.identity;

import java.util.Set;

/**
 * 主体可访问范围（A02）：组织与对象标识的**显式白名单**。
 *
 * <p>DENY 语义是这个类型的核心：空集合不等于"不过滤"，而是"什么都不可访问"。
 * 消费方必须先判断 {@link #isDeny()}，任何"范围为空就查全部"的实现都是越权。
 *
 * @param organizationIds 允许访问的组织标识（业务侧组织体系）
 * @param resourceKeys    允许访问的对象标识（例如报表/知识库/文件的业务键）
 * @param source          范围来源标识（与主体记录的 scopeSource 对应，便于审计）
 * @param version         范围版本（与主体记录的 scopeVersion 对应；同步后递增）
 */
public record SubjectScope(Set<Long> organizationIds, Set<String> resourceKeys, String source, long version) {

    /** 拒绝一切：解析失败、结果为空、超预算、来源不可信都归到这里。 */
    public static final SubjectScope DENY = new SubjectScope(Set.of(), Set.of(), "deny", -1L);

    /** 构造时收敛空值，避免下游对 null 做分支。 */
    public SubjectScope {
        organizationIds = organizationIds == null ? Set.of() : Set.copyOf(organizationIds);
        resourceKeys = resourceKeys == null ? Set.of() : Set.copyOf(resourceKeys);
    }

    /** 是否为拒绝（两个白名单都为空时必须拒绝，不得放行为全部数据）。 */
    public boolean isDeny() {
        return organizationIds.isEmpty() && resourceKeys.isEmpty();
    }

    /** 是否允许访问某个组织。 */
    public boolean allowsOrganization(Long organizationId) {
        return !isDeny() && organizationId != null && organizationIds.contains(organizationId);
    }

    /** 是否允许访问某个对象。 */
    public boolean allowsResource(String resourceKey) {
        return !isDeny() && resourceKey != null && resourceKeys.contains(resourceKey);
    }
}
