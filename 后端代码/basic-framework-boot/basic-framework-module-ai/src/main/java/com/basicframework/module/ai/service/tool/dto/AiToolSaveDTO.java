package com.basicframework.module.ai.service.tool.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 工具新增/修改（服务层 DTO）。 */
@Data
@Accessors(chain = true)
public class AiToolSaveDTO {

    /** 工具编号（修改时必填） */
    private Long id;

    /** 工具标识（创建后不可修改） */
    private String code;

    /** 工具名称 */
    private String name;

    /** 说明（供模型理解用途） */
    private String description;

    /** 连接器编号（创建时必填） */
    private Long connectorId;

    /** 乐观锁版本（修改时必填） */
    private Integer version;
}
