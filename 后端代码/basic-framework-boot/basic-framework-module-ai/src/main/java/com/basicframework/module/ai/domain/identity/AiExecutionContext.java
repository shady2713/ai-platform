package com.basicframework.module.ai.domain.identity;

import java.util.Set;

/**
 * AI 执行上下文（A06）：异步任务/后续步骤使用的**不可变身份与范围快照**。
 *
 * <p>设计约束：
 * <ul>
 *   <li>上下文必须由 {@code AiExecutionContextFactory#rebuild} 从服务端事实重建，
 *       **不依赖 ThreadLocal、不继承上一请求**——同一个线程先后处理两个任务时互不影响；</li>
 *   <li>空范围即 DENY：{@link #isDeny()} 为真时消费方必须停止执行，
 *       不得把"范围为空"当成"不过滤"；</li>
 *   <li>撤销后重建会直接失败（解析返回 DENY），因此"后续受限步骤"在撤销后无法继续。</li>
 * </ul>
 */
public record AiExecutionContext(
        Long applicationId,
        String subjectType,
        String externalUserId,
        Set<Long> organizationIds,
        Set<String> resourceKeys,
        String scopeSource,
        long scopeVersion) {

    public AiExecutionContext {
        organizationIds = organizationIds == null ? Set.of() : Set.copyOf(organizationIds);
        resourceKeys = resourceKeys == null ? Set.of() : Set.copyOf(resourceKeys);
    }

    /** 是否为拒绝上下文（范围为空）。 */
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
