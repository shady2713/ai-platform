package com.basicframework.module.ai.service.evaluation.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 评测套件保存（服务层 DTO，Q04）。 */
@Data
@Accessors(chain = true)
public class AiEvalSuiteSaveDTO {

    /** 套件编号（更新时必填） */
    private Long id;

    /** 乐观锁版本（更新时必填） */
    private Integer version;

    /** 所属应用编号 */
    private Long applicationId;

    /** 套件标识（应用内唯一，创建后不可修改） */
    private String code;

    /** 套件名称 */
    private String name;

    /** 说明 */
    private String description;

    /** 被评测的服务编号 */
    private Long serviceId;

    /** 执行主体类型（APP/USER） */
    private String subjectType;

    /** 执行主体标识（合成主体，不是真实用户） */
    private String externalUserId;

    /** 样例数据分级（只允许 L1_PUBLIC/L2_INTERNAL） */
    private String dataLevel;
}
