package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.connector.importer.AiConnectorOperationService;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.tool.action.AiAnalysisStepScheduler;
import com.basicframework.module.ai.service.tool.action.AiToolActionService;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * X06 受控业务写工具验收夹具（真实 MySQL/Redis + 业务系统模拟器）。
 *
 * <p>每个用例的链路是**真的**：连接器（两个已发布操作：写 {@code createPayment}、核对 {@code getPayment}）
 * → 写工具版本（声明业务幂等键与核对查询）→ 动作（参数冻结 + 一次性挑战）→ 执行（经受控出站边界）
 * → 业务系统模拟器（有状态、可注入故障）→ 核对（按业务键查真实业务事实）。
 *
 * <p>为什么用模拟器而不是真实收款系统：真实写操作需要用户明确授权的生产系统与凭据（未授权即不得执行）。
 * 模拟器不是"打桩跳过"：它按业务规则真的记账、真的按键去重，并可通过
 * {@link AiBusinessWriteSimulator.Fault} 注入超时/中断/5xx 等真实故障形态。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AiToolWriteAcceptanceSupport extends AbstractPersistenceIntegrationTest {

    /** 写工具（收款登记）。 */
    protected static final String WRITE_TOOL_CODE = "it-create-payment";

    /** 读工具（对账查询）：用来验证读路径不受写规则影响。 */
    protected static final String READ_TOOL_CODE = "it-query-payments";

    /** 指向未授权目标的写工具：验证外发只能走受控出站（出站策略拒绝 = 确定未生效）。 */
    protected static final String BLOCKED_WRITE_TOOL_CODE = "it-create-payment-blocked";

    /** 连接器（模拟器）。 */
    protected static final String CONNECTOR_CODE = "it-write-sim-connector";

    /** 未授权目标的连接器。 */
    protected static final String BLOCKED_CONNECTOR_CODE = "it-write-blocked-connector";

    protected static final String APP_CODE = "it-write-app";

    protected static final String SUBJECT_TYPE = "USER";

    protected static final String USER = "it-write-user-001";

    protected static final String INPUT_SCHEMA =
            """
            {"payment_no": {"type": "string", "required": true},
             "amount": {"type": "number", "required": true}}
            """;

    /** 写绑定声明（输出 schema 的保留键 write）：幂等键 payment_no，核对查询 getPayment。 */
    protected static final String WRITE_BINDING_OUTPUT_SCHEMA =
            """
            {"columns": [{"code": "payment_no"}],
             "write": {"idempotencyParam": "payment_no", "reconcileOperation": "getPayment",
                       "reconcileParam": "payment_no"}}
            """;

    private static final String OPEN_API_DOCUMENT =
            """
            {"openapi": "3.0.0",
             "info": {"title": "IT 收款系统", "version": "1.0.0"},
             "paths": {
               "/payments": {"post": {"operationId": "createPayment", "summary": "登记收款",
                 "requestBody": {"required": true, "content": {"application/json": {"schema": {
                   "type": "object", "required": ["payment_no", "amount"],
                   "properties": {"payment_no": {"type": "string"}, "amount": {"type": "number"}}}}}},
                 "responses": {"200": {"description": "ok"}}}},
               "/payments/query": {"get": {"operationId": "getPayment", "summary": "按业务键查收款",
                 "parameters": [{"name": "payment_no", "in": "query", "required": true,
                                 "schema": {"type": "string"}}],
                 "responses": {"200": {"description": "ok"}}}},
               "/payments/other": {"get": {"operationId": "getPaymentOther", "summary": "另一个核对查询",
                 "parameters": [{"name": "payment_no", "in": "query", "required": true,
                                 "schema": {"type": "string"}}],
                 "responses": {"200": {"description": "ok"}}}}}}
            """;

    @Autowired
    protected AiConnectorService connectorService;

    @Autowired
    protected AiConnectorOperationService operationService;

    @Autowired
    protected AiToolService toolService;

    @Autowired
    protected AiToolActionService actionService;

    @Autowired
    protected AiAnalysisStepScheduler stepScheduler;

    @Autowired
    protected AiToolPolicyGate policyGate;

    @Autowired
    protected AiToolExecutor toolExecutor;

    @Autowired
    protected AiBusinessWriteSimulator simulator;

    protected Long connectorId;

    protected Long blockedConnectorId;

    protected Long writeToolId;

    protected Long writeVersionId;

    protected Long readToolId;

    protected Long readVersionId;

    protected Long runId;

    protected Long applicationId;

    protected Long serviceId;

    protected Long releaseId;

    protected Long conversationId;

    /** 与既有 AI IT 同源：替身作为受控出站边界注入（真实边界只对未授权目标生效）。 */
    @TestConfiguration
    static class WriteSimulatorConfiguration {

        @Bean
        @Primary
        ExternalHttpClient writeSimulatorHttpClient() {
            return new AiBusinessWriteSimulator();
        }
    }

    @BeforeEach
    void prepareWriteToolChain() {
        simulator.reset();
        connectorId = createConnector(CONNECTOR_CODE, AiBusinessWriteSimulator.SIMULATOR_ORIGIN);
        publishOperations(connectorId);
        blockedConnectorId = createConnector(BLOCKED_CONNECTOR_CODE, "https://crm.example.com");
        publishOperations(blockedConnectorId);

        writeToolId = toolService.create(
                new AiToolSaveDTO().setCode(WRITE_TOOL_CODE).setName("登记收款").setConnectorId(connectorId));
        writeVersionId = toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(writeToolId)
                .setToolType("WRITE")
                .setPolicy("CONFIRM")
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef("createPayment")
                .setInputSchemaJson(INPUT_SCHEMA)
                .setOutputSchemaJson(WRITE_BINDING_OUTPUT_SCHEMA));
        toolService.publishVersion(
                writeVersionId, toolService.getVersion(writeVersionId).getVersion());

        // 读工具（对账直查）：用来验证读路径不受写规则影响，且写判定不能走通用执行入口
        readToolId = toolService.create(
                new AiToolSaveDTO().setCode(READ_TOOL_CODE).setName("对账查询").setConnectorId(connectorId));
        readVersionId = toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(readToolId)
                .setToolType("READ")
                .setPolicy("AUTO")
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef("getPayment")
                .setInputSchemaJson(INPUT_SCHEMA)
                .setOutputSchemaJson("{\"columns\":[]}"));
        publish(readVersionId);

        prepareRunChain();
    }

    @AfterEach
    void cleanWriteToolChain() {
        cleanupChain();
        jdbcTemplate.update(
                "DELETE FROM ai_tool_version WHERE tool_id IN (SELECT id FROM ai_tool WHERE code IN (?, ?, ?))",
                WRITE_TOOL_CODE,
                READ_TOOL_CODE,
                BLOCKED_WRITE_TOOL_CODE);
        jdbcTemplate.update(
                "DELETE FROM ai_tool WHERE code IN (?, ?, ?)",
                WRITE_TOOL_CODE,
                READ_TOOL_CODE,
                BLOCKED_WRITE_TOOL_CODE);
        for (Long id : new Long[] {connectorId, blockedConnectorId}) {
            if (id != null) {
                jdbcTemplate.update("DELETE FROM ai_connector_operation WHERE connector_id = ?", id);
            }
        }
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code IN (?, ?)", CONNECTOR_CODE, BLOCKED_CONNECTOR_CODE);
        connectorId = null;
        blockedConnectorId = null;
        writeToolId = null;
        writeVersionId = null;
        readToolId = null;
        readVersionId = null;
        runId = null;
        simulator.reset();
    }

    private Long createConnector(String code, String baseUrl) {
        return connectorService.create(new AiConnectorSaveDTO()
                .setCode(code)
                .setName("IT 写工具连接器 " + code)
                .setConnectorType(AiConnectorDO.TYPE_HTTP)
                .setConfigJson("{\"baseUrl\":\"" + baseUrl + "\",\"method\":\"POST\"}"));
    }

    /**
     * 导入并发布操作（写 + 核对查询）：写工具的执行与核对都只能落在已发布操作上。
     *
     * <p>核对查询的结果提取路径直接落库声明为 {@code items}（导入器的默认声明是"整个响应体为一条结果"）：
     * 声明式字段本身来自 D02 的操作草稿，本卡不改连接器导入实现，只把夹具要用的声明写进去。
     */
    private void publishOperations(Long id) {
        operationService.importOperations(id, OPEN_API_DOCUMENT);
        jdbcTemplate.update(
                "UPDATE ai_connector_operation SET response_json = ? WHERE connector_id = ? AND operation_key = ?",
                "{\"rootPath\":\"\",\"listPath\":\"items\"}",
                id,
                "getPayment");
        for (var operation : operationService.listOperations(id)) {
            operationService.publish(operation.getId(), operation.getVersion());
        }
    }

    /** 运行行与其 FK 链（应用/服务/发布/会话）直接落库：受理链路由 O01/O04 的证据覆盖。 */
    private void prepareRunChain() {
        cleanupChain();
        jdbcTemplate.update(
                "INSERT INTO ai_application (app_code, name, origins, enabled, creator, updater)"
                        + " VALUES (?, 'IT 写工具应用', '[]', b'1', 'it', 'it')",
                APP_CODE);
        applicationId =
                jdbcTemplate.queryForObject("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        jdbcTemplate.update(
                "INSERT INTO ai_service (app_id, code, name, status, model_endpoint_id, prompt_template,"
                        + " input_schema, required_capabilities, run_subject_type, creator, updater)"
                        + " VALUES (?, 'it-write-service', 'IT 写工具服务', 'READY', 1, 'prompt', '{}', 'TEXT',"
                        + " 'USER', 'it', 'it')",
                applicationId);
        serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_service WHERE code = 'it-write-service' AND app_id = ?", Long.class, applicationId);
        jdbcTemplate.update(
                "INSERT INTO ai_service_release (service_id, model_endpoint_id, endpoint_config_revision,"
                        + " prompt_template, input_schema, required_capabilities, content_hash, status, release_version,"
                        + " creator, updater)"
                        + " VALUES (?, 1, 1, 'prompt', '{}', 'TEXT', ?, 'ACTIVE', 1, 'it', 'it')",
                serviceId,
                "a".repeat(64));
        releaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_service_release WHERE service_id = ?", Long.class, serviceId);
        jdbcTemplate.update(
                "INSERT INTO ai_conversation (application_id, subject_type, external_user_id,"
                        + " conversation_key, title, business_context, creator, updater)"
                        + " VALUES (?, 'USER', ?, ?, 'IT 写工具会话', '{}', 'it', 'it')",
                applicationId,
                USER,
                "conv_write_" + System.nanoTime());
        conversationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversation WHERE external_user_id = ? ORDER BY id DESC LIMIT 1", Long.class, USER);
        jdbcTemplate.update(
                "INSERT INTO ai_run (run_key, application_id, subject_type, external_user_id,"
                        + " conversation_id, service_id, release_id, model_endpoint_id, endpoint_config_revision,"
                        + " content_hash, input_digest, status, step_count, creator, updater)"
                        + " VALUES (?, ?, 'USER', ?, ?, ?, ?, 1, 1, ?, ?, 'RUNNING', 0, 'it', 'it')",
                "run_write_" + System.nanoTime(),
                applicationId,
                USER,
                conversationId,
                serviceId,
                releaseId,
                "b".repeat(64),
                "c".repeat(64));
        runId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_run WHERE external_user_id = ? ORDER BY id DESC LIMIT 1", Long.class, USER);
    }

    /** 清理运行行与其 FK 链：让每个用例的 setup 可重复执行（动作随运行一起清掉）。 */
    private void cleanupChain() {
        jdbcTemplate.update(
                "DELETE FROM ai_tool_action WHERE run_id IN"
                        + " (SELECT id FROM ai_run WHERE application_id IN"
                        + " (SELECT id FROM ai_application WHERE app_code = ?))",
                APP_CODE);
        jdbcTemplate.update(
                "DELETE FROM ai_run WHERE application_id IN" + " (SELECT id FROM ai_application WHERE app_code = ?)",
                APP_CODE);
        jdbcTemplate.update(
                "DELETE FROM ai_conversation WHERE application_id IN"
                        + " (SELECT id FROM ai_application WHERE app_code = ?)",
                APP_CODE);
        jdbcTemplate.update(
                "DELETE FROM ai_service_release WHERE service_id IN"
                        + " (SELECT id FROM ai_service WHERE app_id IN"
                        + " (SELECT id FROM ai_application WHERE app_code = ?))",
                APP_CODE);
        jdbcTemplate.update(
                "DELETE FROM ai_service WHERE app_id IN" + " (SELECT id FROM ai_application WHERE app_code = ?)",
                APP_CODE);
        jdbcTemplate.update("DELETE FROM ai_application WHERE app_code = ?", APP_CODE);
    }

    protected static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    /** 在既有工具上创建一个版本草稿（不发布）：给"发布前必须先声明"的用例使用。 */
    protected Long createDraftVersion(Long toolId, String toolType, String policy, String outputSchemaJson) {
        return createDraftVersion(toolId, toolType, policy, "createPayment", outputSchemaJson);
    }

    /** 同上，指定来源操作（读工具用 GET 操作）。 */
    protected Long createDraftVersion(
            Long toolId, String toolType, String policy, String sourceRef, String outputSchemaJson) {
        return toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(toolId)
                .setToolType(toolType)
                .setPolicy(policy)
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef(sourceRef)
                .setInputSchemaJson(INPUT_SCHEMA)
                .setOutputSchemaJson(outputSchemaJson));
    }

    /** 发布一个版本（用当前乐观锁版本）。 */
    protected void publish(Long versionId) {
        toolService.publishVersion(versionId, toolService.getVersion(versionId).getVersion());
    }

    /** 步骤请求（写工具调用）。 */
    protected com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepRequestDTO writeStepRequest(
            java.util.Map<String, Object> arguments) {
        return new com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepRequestDTO()
                .setRunId(runId)
                .setApplicationId(applicationId)
                .setSubjectType(SUBJECT_TYPE)
                .setExternalUserId(USER)
                .setToolCode(WRITE_TOOL_CODE)
                .setArguments(arguments);
    }

    /** 从"待确认"一路走到"已确认"，返回动作编号。 */
    protected Long createAndConfirmAction(java.util.Map<String, Object> arguments) {
        var step = stepScheduler.executeStep(writeStepRequest(arguments));
        actionService.confirm(step.getActionId(), applicationId, SUBJECT_TYPE, USER, step.getChallenge(), arguments);
        return step.getActionId();
    }
}
