package com.basicframework.module.ai.domain.policy;

import java.util.Set;

/**
 * 模型外发策略（M05）：决定"某个端点在本次调用中允许接收什么等级的数据"。
 *
 * <p>策略在**任何网络调用之前**执行：资源等级不被允许时直接拒绝，不发出请求、
 * 也不尝试其他端点（不做隐式跨供应商失败转移）。
 */
public interface AiOutboundPolicy {

    /** 端点允许接收的最高数据等级。 */
    AiOutboundLevel levelOf(Long endpointId);

    /** 端点显式允许外发的资源等级集合。 */
    Set<AiOutboundLevel> allowedResourcesOf(Long endpointId);

    /**
     * 校验资源等级是否允许外发到该端点；不允许时抛出稳定错误（403），调用方不得继续发起请求。
     *
     * @param endpointId   端点编号
     * @param resourceLevel 本次将外发的资源等级
     */
    void assertAllowed(Long endpointId, AiOutboundLevel resourceLevel);
}
