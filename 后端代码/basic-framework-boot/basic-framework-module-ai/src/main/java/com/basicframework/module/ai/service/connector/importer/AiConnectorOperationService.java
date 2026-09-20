package com.basicframework.module.ai.service.connector.importer;

import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.service.connector.importer.dto.AiOpenApiImportResultDTO;
import java.util.List;

/**
 * 连接器操作服务（D02）：导入草稿、发布与查询。
 *
 * <p>导入结果一律是 DRAFT；只有显式发布（`ai:connector:operation` 权限）后才可执行。
 * 重复导入同一操作标识时按"更新草稿内容并回到 DRAFT"处理——已发布的操作被改动后必须重新发布，
 * 避免"导入即悄悄替换线上行为"。
 */
public interface AiConnectorOperationService {

    /** 从 OpenAPI 文档导入操作草稿（返回导入结果与被跳过项）。 */
    AiOpenApiImportResultDTO importOperations(Long connectorId, String documentJson);

    /** 发布操作（乐观锁；草稿才可发布）。 */
    void publish(Long operationId, Integer version);

    /** 某连接器的操作（按编号升序）。 */
    List<AiConnectorOperationDO> listOperations(Long connectorId);

    /** 单个操作。 */
    AiConnectorOperationDO getOperation(Long operationId);
}
