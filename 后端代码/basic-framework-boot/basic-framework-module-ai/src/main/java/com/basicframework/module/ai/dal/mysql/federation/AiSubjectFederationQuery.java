package com.basicframework.module.ai.dal.mysql.federation;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * 联邦映射的持久层查询条件（Y01）：六段身份，APP 主体的外部标识归一化为空串。
 *
 * <p>只表达查询条件：不带 Swagger、Controller 校验或序列化注解（ADR 0004）。
 */
@Getter
@Setter
@Accessors(chain = true)
public final class AiSubjectFederationQuery {

    /** 来源应用编号 */
    private Long sourceApplicationId;

    /** 来源主体类型（USER/APP） */
    private String sourceSubjectType;

    /** 来源主体外部用户标识 */
    private String sourceExternalUserId;

    /** 目标应用编号 */
    private Long targetApplicationId;

    /** 目标主体类型（USER/APP） */
    private String targetSubjectType;

    /** 目标主体外部用户标识 */
    private String targetExternalUserId;

    /** APP 主体的外部标识固定为空串，避免同一应用主体因空格/大小写差异生成多行。 */
    public String normalizedSourceExternalUserId() {
        return sourceExternalUserId == null ? "" : sourceExternalUserId;
    }

    /** 同上：目标侧同样归一化。 */
    public String normalizedTargetExternalUserId() {
        return targetExternalUserId == null ? "" : targetExternalUserId;
    }
}
