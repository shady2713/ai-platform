package com.basicframework.framework.ai.core.model;

/**
 * 模型客户端缓存键（M02 冻结）：端点标识 + 非秘密配置版本 + 凭据版本。
 *
 * <p>凭据本身绝不进入键：轮换后 credentialRevision 变化即得到新的键，
 * 旧客户端不再接受新请求。
 */
public record ModelEndpointKey(Long endpointId, Integer configRevision, Integer credentialRevision) {}
