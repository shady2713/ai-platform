package com.basicframework.module.ai.service.authorization.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 跨系统主体联邦映射登记请求（Y01）。
 *
 * <p>六个字段都是**身份事实**：来源/目标各自给出应用、主体类型与外部用户标识。外部用户标识
 * 由服务端登记（业务侧断言），不接受"同名即同一人"的推断，也不接受角色/部门等客户端可伪造字段。
 */
@Data
@Accessors(chain = true)
public class AiSubjectFederationSubmitDTO {

    /** 来源系统（应用）编号 */
    private Long sourceApplicationId;

    /** 来源主体类型（USER/APP） */
    private String sourceSubjectType;

    /** 来源主体外部用户标识（APP 主体忽略，归一化为空串） */
    private String sourceExternalUserId;

    /** 目标系统（应用）编号 */
    private Long targetApplicationId;

    /** 目标主体类型（USER/APP） */
    private String targetSubjectType;

    /** 目标主体外部用户标识（APP 主体忽略，归一化为空串） */
    private String targetExternalUserId;
}
