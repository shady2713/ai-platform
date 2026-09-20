package com.basicframework.module.ai.adapter.connector.http.dto;

import java.util.Map;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 连接器操作执行请求（D02）：只允许提供**参数值**，不能提供 URL 或请求头。 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"arguments"})
public class AiConnectorExecutionRequestDTO {

    /** 连接器编号 */
    private Long connectorId;

    /** 操作标识 */
    private String operationKey;

    /** 参数值（键必须来自操作声明） */
    private Map<String, Object> arguments;
}
