package com.basicframework.module.ai.service.application.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 多系统授权发现请求（Y01）。
 *
 * <p>三个字段都是服务端事实：应用、主体类型、外部用户标识。发现不接受任何"我想看哪个系统"的
 * 参数——无权系统**根本不出现**在结果里（而不是出现后标注无权），因此也没有可枚举的参数面。
 */
@Data
@Accessors(chain = true)
public class AiSystemCatalogQueryDTO {

    /** 当前应用（当前系统）编号 */
    private Long applicationId;

    /** 当前主体类型（USER/APP） */
    private String subjectType;

    /** 当前主体外部用户标识（APP 主体忽略，归一化为空串） */
    private String externalUserId;
}
