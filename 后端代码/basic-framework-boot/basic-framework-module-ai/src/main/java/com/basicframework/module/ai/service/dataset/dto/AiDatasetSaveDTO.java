package com.basicframework.module.ai.service.dataset.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 数据集新增/修改（服务层 DTO）：来源对象与标识创建后不可修改。 */
@Data
@Accessors(chain = true)
public class AiDatasetSaveDTO {

    /** 数据集编号（修改时必填） */
    private Long id;

    /** 数据集标识（创建后不可修改） */
    private String code;

    /** 数据集名称 */
    private String name;

    /** 说明 */
    private String description;

    /** 连接器编号（创建时必填） */
    private Long connectorId;

    /** 来源对象（schema.table，必须在该连接器授权白名单内；创建后不可修改） */
    private String sourceObject;

    /** 乐观锁版本（修改时必填） */
    private Integer version;
}
