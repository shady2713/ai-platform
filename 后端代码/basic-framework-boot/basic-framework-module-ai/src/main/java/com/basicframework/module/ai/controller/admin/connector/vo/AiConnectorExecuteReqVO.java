package com.basicframework.module.ai.controller.admin.connector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 执行连接器操作（协议层 VO）：只能提供**参数值**。
 *
 * <p>请求体没有 URL 与请求头字段：目标地址只来自连接器 baseUrl 与操作路径模板，
 * 请求头只来自连接器认证配置——调用方（包括模型）无法替换。
 */
@Schema(description = "管理后台 - 执行连接器操作")
@Data
@Accessors(chain = true)
@ToString(exclude = {"arguments"})
public class AiConnectorExecuteReqVO {

    @Schema(description = "连接器编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long connectorId;

    @Schema(description = "操作标识（已发布）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private String operationKey;

    @Schema(description = "参数值（键必须来自操作声明；只接受标量）")
    private Map<String, Object> arguments;
}
