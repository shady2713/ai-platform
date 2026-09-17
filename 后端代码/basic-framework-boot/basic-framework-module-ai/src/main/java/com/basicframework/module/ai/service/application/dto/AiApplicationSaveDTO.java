package com.basicframework.module.ai.service.application.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 应用新增/修改（服务层 DTO）：协议层 VO 由控制器转换，服务层不依赖 VO。 */
@Data
@Accessors(chain = true)
public class AiApplicationSaveDTO {

    /** 应用编号（修改时必填） */
    private Long id;

    /** 应用标识（新增时必填；创建后不可修改） */
    private String appCode;

    /** 应用名称 */
    private String name;

    /** 应用说明 */
    private String description;

    /** 精确 Origin 列表（原样提交，服务层校验并归一化） */
    private List<String> origins;

    /** 乐观锁版本（修改时必填） */
    private Integer version;
}
