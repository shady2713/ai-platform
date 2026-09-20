package com.basicframework.module.ai.service.connector.importer.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 导入结果（D02）：导入的操作草稿 + 被跳过的操作及原因（可审计，不静默丢弃）。 */
@Data
@Accessors(chain = true)
public class AiOpenApiImportResultDTO {

    /** 导入的操作草稿 */
    private List<AiConnectorOperationDraftDTO> operations;

    /** 被跳过的操作说明（例如外部 $ref、非 GET/POST、header 参数） */
    private List<String> skipped;
}
