package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.connector.importer.AiConnectorOperationService;
import com.basicframework.module.ai.service.connector.importer.dto.AiOpenApiImportResultDTO;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D08 工具注册与执行政策端到端（真实 MySQL）：工具/版本落库、政策默认 DENY、
 * 首期只发布读工具、来源 operation 必须已发布、伪造工具名/参数的拒绝与引用保护。
 *
 * <p>真实 HTTP 调用不在本 IT 范围（出站策略默认拒绝，见证据文档"未验证项"）；
 * 本 IT 覆盖注册表与政策判定（判定不发起网络请求）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiToolRegistryIT extends AbstractPersistenceIntegrationTest {

    private static final String CONNECTOR_CODE = "it-tool-connector";

    private static final String TOOL_CODE = "it-query-orders";

    private static final String OPEN_API_DOCUMENT =
            """
            {"openapi": "3.0.0",
             "info": {"title": "IT 订单接口", "version": "1.0.0"},
             "paths": {"/orders": {"get": {"operationId": "getOrders", "summary": "查询订单",
               "parameters": [{"name": "region", "in": "query", "required": true,
                               "schema": {"type": "string"}}],
               "responses": {"200": {"description": "ok"}}}}}}
            """;

    private static final String INPUT_SCHEMA =
            """
            {"region": {"type": "string", "required": true}}
            """;

    private static final String OUTPUT_SCHEMA =
            """
            {"columns": [{"code": "net_amount", "type": "DECIMAL", "unit": "CURRENCY"}]}
            """;

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiConnectorOperationService operationService;

    @Autowired
    private AiToolService toolService;

    @Autowired
    private AiToolPolicyGate policyGate;

    private Long connectorId;

    private Long operationId;

    private Long toolId;

    private Long versionId;

    @BeforeEach
    void prepareRegistry() {
        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("IT 工具连接器")
                .setConnectorType(AiConnectorDO.TYPE_HTTP)
                .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}"));
        AiOpenApiImportResultDTO imported = operationService.importOperations(connectorId, OPEN_API_DOCUMENT);
        assertThat(imported.getOperations()).hasSize(1);
        assertThat(imported.getOperations().get(0).getOperationKey()).isEqualTo("getOrders");
        // 导入产生草稿：按 operationKey 找到落库行并发布
        operationId = operationService.listOperations(connectorId).stream()
                .filter(operation -> "getOrders".equals(operation.getOperationKey()))
                .findFirst()
                .orElseThrow()
                .getId();
        operationService.publish(
                operationId, operationService.getOperation(operationId).getVersion());

        toolId = toolService.create(new AiToolSaveDTO()
                .setCode(TOOL_CODE)
                .setName("查询订单")
                .setDescription("按区域查询订单净额")
                .setConnectorId(connectorId));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM ai_tool_version WHERE tool_id IN (SELECT id FROM ai_tool WHERE code = ?)", TOOL_CODE);
        jdbcTemplate.update("DELETE FROM ai_tool WHERE code = ?", TOOL_CODE);
        jdbcTemplate.update("DELETE FROM ai_connector_operation WHERE connector_id = ?", connectorId);
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code = ?", CONNECTOR_CODE);
        toolId = null;
        versionId = null;
        connectorId = null;
        operationId = null;
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private Long createVersion(String toolType, String policy) {
        return toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(toolId)
                .setToolType(toolType)
                .setPolicy(policy)
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef("getOrders")
                .setInputSchemaJson(INPUT_SCHEMA)
                .setOutputSchemaJson(OUTPUT_SCHEMA));
    }

    private void publish(Long version) {
        toolService.publishVersion(version, toolService.getVersion(version).getVersion());
    }

    @Test
    void publishesReadToolAndExecutesPolicyMatrix() {
        versionId = createVersion("READ", "AUTO");
        publish(versionId);

        AiToolVersionDO published = toolService.getVersion(versionId);
        assertThat(published.getStatus()).isEqualTo(AiToolVersionDO.STATUS_PUBLISHED);
        assertThat(published.getSchemaHash()).hasSize(64);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(toolService.getTool(toolId).getLatestVersionNo()).isEqualTo(1);

        // AUTO：判定可执行，参数已按 schema 归一
        assertThat(policyGate.decide(TOOL_CODE, Map.of("region", "EAST")).executable())
                .isTrue();

        // 伪造参数 / 伪造工具名
        assertThatThrownBy(() -> policyGate.decide(TOOL_CODE, Map.of("region", "EAST", "admin", "1")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ARGUMENT_INVALID));
        assertThatThrownBy(() -> policyGate.decide("forged-tool", Map.of("region", "EAST")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_NOT_FOUND));
    }

    @Test
    void policyIsImmutablePerVersionAndDefaultsToDeny() {
        Long denied = createVersion("READ", null);
        assertThat(toolService.getVersion(denied).getPolicy()).as("不写政策就是 DENY").isEqualTo("DENY");
        publish(denied);
        assertThatThrownBy(() -> policyGate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_POLICY_DENIED));

        // 改政策必须新建版本：新版本 CONFIRM 后判定为"需确认"
        Long confirm = createVersion("READ", "CONFIRM");
        publish(confirm);
        assertThatThrownBy(() -> policyGate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED));
        assertThat(policyGate
                        .decideAfterConfirmation(TOOL_CODE, Map.of("region", "EAST"))
                        .executable())
                .as("确认后执行路径可用，且来源仍来自版本快照")
                .isTrue();

        // 已发布版本不可再次发布（不可变）
        assertThatThrownBy(() -> publish(confirm))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void refusesWriteToolAndUnpublishedSource() {
        // 首期只发布读工具
        Long writeVersion = createVersion("WRITE", "AUTO");
        assertThatThrownBy(() -> publish(writeVersion))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_TYPE_UNSUPPORTED));

        // 来源 operation 未发布：把已发布 operation 退回草稿后拒绝发布
        jdbcTemplate.update("UPDATE ai_connector_operation SET status = 'DRAFT' WHERE id = ?", operationId);
        Long draftSource = createVersion("READ", "AUTO");
        assertThatThrownBy(() -> publish(draftSource))
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED));

        // 来源 operation 不存在
        Long missingSource = toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(toolId)
                .setToolType("READ")
                .setPolicy("AUTO")
                .setSourceRef("missingOperation")
                .setInputSchemaJson(INPUT_SCHEMA)
                .setOutputSchemaJson(OUTPUT_SCHEMA));
        assertThatThrownBy(() -> publish(missingSource))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND));
    }

    @Test
    void disabledToolBehavesLikeUnknownAndConnectorDeletionIsProtected() {
        versionId = createVersion("READ", "AUTO");
        publish(versionId);

        AiToolDO tool = toolService.getTool(toolId);
        toolService.updateStatus(toolId, tool.getVersion(), false);
        assertThatThrownBy(() -> policyGate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .as("停用工具与不存在同码")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_NOT_FOUND));

        toolService.updateStatus(toolId, toolService.getTool(toolId).getVersion(), true);
        assertThat(policyGate.decide(TOOL_CODE, Map.of("region", "EAST")).executable())
                .isTrue();

        // 工具在用时连接器不可删除（D08 注册的引用检查生效）
        assertThatThrownBy(() -> connectorService.delete(
                        connectorId, connectorService.getConnector(connectorId).getVersion()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_REFERENCED));

        // 工具分页与版本分页
        assertThat(toolService
                        .getToolPage(new com.basicframework.framework.common.pojo.PageParam(), connectorId, null)
                        .getList())
                .extracting(AiToolDO::getCode)
                .contains(TOOL_CODE);
        assertThat(toolService
                        .getVersionPage(toolId, new com.basicframework.framework.common.pojo.PageParam())
                        .getTotal())
                .isEqualTo(1L);
        assertThat(List.of(toolService.getVersion(versionId)).get(0).getToolType())
                .isEqualTo("READ");
    }
}
