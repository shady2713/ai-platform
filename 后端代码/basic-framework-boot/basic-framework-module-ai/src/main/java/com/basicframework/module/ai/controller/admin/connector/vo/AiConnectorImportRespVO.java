package com.basicframework.module.ai.controller.admin.connector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 导入结果（协议层 VO）：导入的操作草稿标识 + 被跳过项（可审计）。 */
@Schema(description = "管理后台 - 连接器 OpenAPI 导入结果")
@Data
@Accessors(chain = true)
public class AiConnectorImportRespVO {

    @Schema(description = "导入的操作标识（草稿，需发布后才可执行）")
    private List<String> operationKeys;

    @Schema(description = "被跳过的操作说明（外部 $ref、非 GET/POST、header 参数等）")
    private List<String> skipped;
}
