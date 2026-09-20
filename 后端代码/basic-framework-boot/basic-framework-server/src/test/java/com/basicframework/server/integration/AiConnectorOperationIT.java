package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.http.AiHttpConnectorExecutor;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionRequestDTO;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.connector.importer.AiConnectorOperationService;
import com.basicframework.module.ai.service.connector.importer.dto.AiOpenApiImportResultDTO;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D02 声明式 HTTP 连接器端到端（真实 MySQL）：OpenAPI 导入 → 草稿 → 发布 → 执行。
 *
 * <p>执行必须经过平台受控出站客户端：本环境未配置出站允许清单，因此真实调用以
 * 稳定原因码结束（策略拒绝或 AI 能力未启用），不会出现"假成功"。
 */
class AiConnectorOperationIT extends AbstractPersistenceIntegrationTest {

    private static final String CODE = "it-http-connector";

    private static final String DOCUMENT =
            """
            {
              "openapi": "3.0.3",
              "info": {"title": "CRM", "version": "1.0"},
              "paths": {
                "/orders/{id}": {
                  "get": {
                    "operationId": "getOrder",
                    "parameters": [
                      {"name": "id", "in": "path", "required": true, "schema": {"type": "string"}},
                      {"name": "tenant", "in": "query", "schema": {"type": "string"}},
                      {"name": "X-Trace", "in": "header", "schema": {"type": "string"}}
                    ]
                  }
                },
                "/orders": {"put": {"operationId": "replaceOrder"}}
              }
            }
            """;

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiConnectorOperationService operationService;

    @Autowired
    private AiHttpConnectorExecutor executor;

    @AfterEach
    void cleanUp() {
        java.util.List<Long> connectorIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_connector WHERE code = ?", Long.class, CODE);
        for (Long connectorId : connectorIds) {
            jdbcTemplate.update("DELETE FROM ai_connector_operation WHERE connector_id = ?", connectorId);
            jdbcTemplate.update("DELETE FROM ai_connector_probe WHERE connector_id = ?", connectorId);
            jdbcTemplate.update("DELETE FROM ai_connector WHERE id = ?", connectorId);
        }
    }

    private Long createHttpConnector() {
        return connectorService.create(new AiConnectorSaveDTO()
                .setCode(CODE)
                .setName("IT HTTP 连接器")
                .setConnectorType(AiConnectorDO.TYPE_HTTP)
                .setConfigJson("{\"baseUrl\":\"https://crm.invalid\",\"method\":\"GET\",\"authType\":\"BEARER\"}")
                .setCredential("it-http-connector-token"));
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void importCreatesDraftsAndPublishIsRequiredBeforeExecution() {
        Long connectorId = createHttpConnector();

        AiOpenApiImportResultDTO imported = operationService.importOperations(connectorId, DOCUMENT);

        assertThat(imported.getOperations()).hasSize(1);
        assertThat(imported.getOperations().get(0).getOperationKey()).isEqualTo("getOrder");
        assertThat(imported.getSkipped())
                .as("PUT 操作与 header 参数都被跳过并记录原因")
                .anySatisfy(reason -> assertThat(reason).contains("只支持 GET/POST"))
                .anySatisfy(reason -> assertThat(reason).contains("忽略 header 参数"));

        // 导入结果落库为 DRAFT，且参数声明里没有 header 参数
        AiConnectorOperationDO draft =
                operationService.listOperations(connectorId).get(0);
        assertThat(draft.getStatus()).isEqualTo(AiConnectorOperationDO.STATUS_DRAFT);
        assertThat(draft.getParameterJson()).contains("id").doesNotContain("X-Trace");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_connector_operation WHERE connector_id = ? AND status = 'DRAFT'",
                        Integer.class,
                        connectorId))
                .isEqualTo(1);

        // 草稿不可执行
        assertThatThrownBy(() -> executor.execute(new AiConnectorExecutionRequestDTO()
                        .setConnectorId(connectorId)
                        .setOperationKey("getOrder")
                        .setArguments(Map.of("id", "A-1"))))
                .satisfies(
                        exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED));

        // 发布后可执行：受控出站策略拒绝（本环境无允许清单）→ 稳定原因码，不是假成功
        operationService.publish(draft.getId(), draft.getVersion());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_connector_operation WHERE id = ?", String.class, draft.getId()))
                .isEqualTo(AiConnectorOperationDO.STATUS_PUBLISHED);

        AiConnectorExecutionResultDTO result = executor.execute(new AiConnectorExecutionRequestDTO()
                .setConnectorId(connectorId)
                .setOperationKey("getOrder")
                .setArguments(Map.of("id", "A-1", "tenant", "tenant-a")));
        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getDetailCode())
                .as("失败只给稳定原因码")
                .isIn("TARGET_NOT_ALLOWED", "AI_DISABLED", "TRANSPORT_FAILED");
        assertThat(result.getStoppedReason()).isEqualTo("upstream-failed");
    }

    @Test
    void reimportReturnsPublishedOperationToDraftSoItMustBeRepublished() {
        Long connectorId = createHttpConnector();
        operationService.importOperations(connectorId, DOCUMENT);
        AiConnectorOperationDO draft =
                operationService.listOperations(connectorId).get(0);
        operationService.publish(draft.getId(), draft.getVersion());

        // 再次导入：声明内容可能变化，因此必须回到 DRAFT 并重新发布
        operationService.importOperations(connectorId, DOCUMENT);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_connector_operation WHERE id = ?", String.class, draft.getId()))
                .as("重新导入后必须重新发布，避免导入即悄悄替换线上行为")
                .isEqualTo(AiConnectorOperationDO.STATUS_DRAFT);
        assertThatThrownBy(() -> operationService.publish(draft.getId(), draft.getVersion()))
                .as("已发布版本号已过期（重新导入推进了乐观锁）")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));

        // 用当前版本重新发布后再次可执行
        AiConnectorOperationDO current = operationService.getOperation(draft.getId());
        operationService.publish(current.getId(), current.getVersion());
        assertThat(operationService.getOperation(draft.getId()).getStatus())
                .isEqualTo(AiConnectorOperationDO.STATUS_PUBLISHED);
    }

    @Test
    void importRejectsNonHttpConnectorAndUnknownConnector() {
        Long mysqlConnectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CODE + "-mysql")
                .setName("IT MySQL 连接器")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlMappedPort()
                        + ",\"database\":\"basic_framework\",\"username\":\"root\"}")
                .setCredential(mysqlRootPassword()));
        try {
            assertThatThrownBy(() -> operationService.importOperations(mysqlConnectorId, DOCUMENT))
                    .as("OpenAPI 导入只适用于 HTTP 连接器")
                    .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        } finally {
            jdbcTemplate.update("DELETE FROM ai_connector_probe WHERE connector_id = ?", mysqlConnectorId);
            jdbcTemplate.update("DELETE FROM ai_connector WHERE id = ?", mysqlConnectorId);
        }

        assertThatThrownBy(() -> operationService.importOperations(999_999L, DOCUMENT))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND));
        assertThatThrownBy(() -> operationService.importOperations(999_999L, "not-json"))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND));
    }
}
