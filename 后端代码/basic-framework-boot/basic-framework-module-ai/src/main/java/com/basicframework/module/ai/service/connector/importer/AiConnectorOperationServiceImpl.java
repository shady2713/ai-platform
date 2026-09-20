package com.basicframework.module.ai.service.connector.importer;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.service.connector.importer.dto.AiConnectorOperationDraftDTO;
import com.basicframework.module.ai.service.connector.importer.dto.AiOpenApiImportResultDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 连接器操作服务实现（D02）：导入只产出草稿，发布是显式动作。 */
@Service
@RequiredArgsConstructor
public class AiConnectorOperationServiceImpl implements AiConnectorOperationService {

    private final AiConnectorMapper connectorMapper;

    private final AiConnectorOperationMapper operationMapper;

    private final AiOpenApiImporter importer;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiOpenApiImportResultDTO importOperations(Long connectorId, String documentJson) {
        AiConnectorDO connector = connectorId == null ? null : connectorMapper.selectById(connectorId);
        if (connector == null) {
            throw exception(AI_CONNECTOR_NOT_FOUND);
        }
        if (!AiConnectorDO.TYPE_HTTP.equals(connector.getConnectorType())) {
            // 只有 HTTP 连接器支持 OpenAPI 导入（MySQL 连接器走 D03 的 metadata 发现）
            throw exception(AI_REQUEST_INVALID);
        }
        AiOpenApiImportResultDTO imported = importer.importDocument(documentJson);
        for (AiConnectorOperationDraftDTO draft : imported.getOperations()) {
            AiConnectorOperationDO existing = operationMapper.selectByKey(connectorId, draft.getOperationKey());
            String parameterJson = JsonUtils.toJsonString(draft.getParameters());
            String responseJson = JsonUtils.toJsonString(draft.getResponse());
            String paginationJson = JsonUtils.toJsonString(draft.getPagination());
            if (existing == null) {
                operationMapper.insert(new AiConnectorOperationDO()
                        .setConnectorId(connectorId)
                        .setOperationKey(draft.getOperationKey())
                        .setHttpMethod(draft.getHttpMethod())
                        .setPathTemplate(draft.getPathTemplate())
                        .setSummary(draft.getSummary())
                        .setParameterJson(parameterJson)
                        .setResponseJson(responseJson)
                        .setPaginationJson(paginationJson)
                        .setStatus(AiConnectorOperationDO.STATUS_DRAFT)
                        .setVersion(0));
                continue;
            }
            // 已存在（含已发布）：更新声明内容并回到 DRAFT，必须重新发布才生效
            if (operationMapper.updateWithVersion(
                            new AiConnectorOperationDO()
                                    .setId(existing.getId())
                                    .setHttpMethod(draft.getHttpMethod())
                                    .setPathTemplate(draft.getPathTemplate())
                                    .setSummary(draft.getSummary())
                                    .setParameterJson(parameterJson)
                                    .setResponseJson(responseJson)
                                    .setPaginationJson(paginationJson)
                                    .setStatus(AiConnectorOperationDO.STATUS_DRAFT)
                                    .setVersion((existing.getVersion() == null ? 0 : existing.getVersion()) + 1),
                            existing.getVersion() == null ? 0 : existing.getVersion())
                    == 0) {
                throw exception(AI_STATE_CONFLICT);
            }
        }
        return imported;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long operationId, Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConnectorOperationDO operation = requireOperation(operationId);
        if (!AiConnectorOperationDO.STATUS_DRAFT.equals(operation.getStatus())) {
            // 已发布的操作不能重复发布：重新导入会先回到 DRAFT
            throw exception(AI_STATE_CONFLICT);
        }
        if (operationMapper.updateWithVersion(
                        new AiConnectorOperationDO()
                                .setId(operationId)
                                .setStatus(AiConnectorOperationDO.STATUS_PUBLISHED)
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public List<AiConnectorOperationDO> listOperations(Long connectorId) {
        AiConnectorDO connector = connectorId == null ? null : connectorMapper.selectById(connectorId);
        if (connector == null) {
            throw exception(AI_CONNECTOR_NOT_FOUND);
        }
        return operationMapper.selectByConnector(connectorId);
    }

    @Override
    public AiConnectorOperationDO getOperation(Long operationId) {
        return requireOperation(operationId);
    }

    private AiConnectorOperationDO requireOperation(Long operationId) {
        AiConnectorOperationDO operation = operationId == null ? null : operationMapper.selectById(operationId);
        if (operation == null) {
            throw exception(AI_CONNECTOR_OPERATION_NOT_FOUND);
        }
        return operation;
    }
}
