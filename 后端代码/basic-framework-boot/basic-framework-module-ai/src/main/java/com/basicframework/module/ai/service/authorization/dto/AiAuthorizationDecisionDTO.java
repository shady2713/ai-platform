package com.basicframework.module.ai.service.authorization.dto;

import java.util.Set;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 授权判定结果（A03）。
 *
 * <p>{@code allowed=false} 时 {@code denyReason} 给出稳定原因码；{@code scopeFingerprint} 标识
 * "本次判定基于的主体范围与授权版本"：范围收窄（主体撤销、授权变更）后指纹必然变化，
 * 消费方据此拒绝复用旧结果或读取在此之前的聚合产物。
 */
@Data
@Accessors(chain = true)
public class AiAuthorizationDecisionDTO {

    /** 应用编号 */
    private Long applicationId;

    /** 主体类型 */
    private String subjectType;

    /** 外部用户标识 */
    private String externalUserId;

    /** 资源类型 */
    private String resourceType;

    /** 资源标识 */
    private String resourceKey;

    /** 动作 */
    private String action;

    /** 是否放行 */
    private boolean allowed;

    /** 拒绝原因码（稳定词表） */
    private String denyReason;

    /** 授权版本（命中授权的当前版本） */
    private Long authzRevision;

    /** 范围指纹：主体范围 + 授权版本的稳定摘要；范围收窄后必然变化 */
    private String scopeFingerprint;

    /** 命中的动作白名单（放行时给出，便于调用方按同一集合继续裁剪） */
    private Set<String> grantedActions;
}
