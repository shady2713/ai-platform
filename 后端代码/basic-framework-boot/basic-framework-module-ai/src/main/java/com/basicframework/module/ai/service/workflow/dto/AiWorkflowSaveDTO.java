package com.basicframework.module.ai.service.workflow.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 流程定义保存参数（服务层 DTO，X08）。
 *
 * <p>创建时必填 {@code applicationId}/{@code code}/{@code name}；更新时必填 {@code id}/{@code version}。
 * 标识创建后不可修改，名称与说明可改。
 */
@Data
@Accessors(chain = true)
public class AiWorkflowSaveDTO {

    /** 流程编号（更新必填） */
    private Long id;

    /** 应用编号（创建必填） */
    private Long applicationId;

    /** 流程标识（创建必填，应用内唯一，创建后不可修改） */
    private String code;

    /** 流程名称（必填） */
    private String name;

    /** 流程说明（可选，缺省空串） */
    private String description;

    /** 乐观锁版本（更新必填） */
    private Integer version;
}
