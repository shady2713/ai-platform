package com.basicframework.module.ai.service.serviceconfig.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 资源绑定（服务层 DTO）。 */
@Data
@Accessors(chain = true)
public class AiServiceResourceSaveDTO {

    /** 服务编号 */
    private Long serviceId;

    /** 资源类型（目录词汇） */
    private String resourceType;

    /** 资源标识 */
    private String resourceKey;

    /** 需要的动作（目录词汇） */
    private List<String> actions;
}
