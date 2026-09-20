package com.basicframework.module.ai.service.connector.importer.dto;

import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;

/** 导入得到的操作草稿（D02）：声明式字段，不含脚本与外部引用。 */
@Data
@Accessors(chain = true)
public class AiConnectorOperationDraftDTO {

    /** 操作标识 */
    private String operationKey;

    /** HTTP 方法（GET/POST） */
    private String httpMethod;

    /** 路径模板 */
    private String pathTemplate;

    /** 操作说明 */
    private String summary;

    /** 参数声明：名称 → {in, required, type}（只允许 query/path/body） */
    private Map<String, Map<String, Object>> parameters;

    /** 响应提取规则 */
    private Map<String, Object> response;

    /** 分页规则 */
    private Map<String, Object> pagination;
}
