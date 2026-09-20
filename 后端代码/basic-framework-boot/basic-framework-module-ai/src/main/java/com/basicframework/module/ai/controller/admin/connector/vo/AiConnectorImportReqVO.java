package com.basicframework.module.ai.controller.admin.connector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** OpenAPI 导入请求（协议层 VO）：只接受文档文本，不抓取任何外部地址。 */
@Schema(description = "管理后台 - 连接器 OpenAPI 导入")
@Data
@Accessors(chain = true)
public class AiConnectorImportReqVO {

    @Schema(description = "OpenAPI 3.x 文档文本（JSON；只解析本地 $ref）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 524288)
    private String documentJson;
}
