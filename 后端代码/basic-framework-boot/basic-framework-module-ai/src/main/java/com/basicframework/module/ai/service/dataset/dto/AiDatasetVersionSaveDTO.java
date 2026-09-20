package com.basicframework.module.ai.service.dataset.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 语义版本创建（服务层 DTO）。 */
@Data
@Accessors(chain = true)
public class AiDatasetVersionSaveDTO {

    /** 数据集编号 */
    private Long datasetId;

    /** 语义定义（JSON 对象文本；键与取值见 AiDatasetDefinition） */
    private String definitionJson;
}
