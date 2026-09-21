package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.connector.importer.AiConnectorOperationService;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.tool.action.AiAnalysisStepScheduler;
import com.basicframework.module.ai.service.tool.action.AiToolActionService;
import com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepRequestDTO;
import com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepResultDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D09 工具确认与分析步骤调度端到端（真实 MySQL）：参数冻结 + 一次性挑战、
 * 改参数/换用户/过期不执行、重复确认与重放执行的单赢家语义、取消后不再执行后续步骤。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiToolActionIT extends AbstractPersistenceIntegrationTest {

    private static final String CONNECTOR_CODE = "it-action-connector";

    private static final String TOOL_CODE = "it-confirm-orders";

    private static final String APP_CODE = "it-action-app";

    private static final String SUBJECT_TYPE = "USER";

    private static final String USER = "it-user-001";

    private static final String OPEN_API_DOCUMENT =
            """
            {"openapi": "3.0.0",
             "info": {"title": "IT 确认接口", "version": "1.0.0"},
             "paths": {"/orders": {"get": {"operationId": "getOrders", "summary": "查询订单",
               "parameters": [{"name": "region", "in": "query", "required": true,
                               "schema": {"type": "string"}}],
               "responses": {"200": {"description": "ok"}}}}}}
            """;

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiConnectorOperationService operationService;

    @Autowired
    private AiToolService toolService;

    @Autowired
    private AiToolActionService actionService;

    @Autowired
    private AiAnalysisStepScheduler stepScheduler;

    private Long connectorId;

    private Long toolId;

    private Long runId;

    private Long applicationId;

    private Long serviceId;

    private Long releaseId;

    private Long conversationId;

    @BeforeEach
    void prepare() {
        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("IT 确认连接器")
                .setConnectorType(AiConnectorDO.TYPE_HTTP)
                .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\"}"));
        operationService.importOperations(connectorId, OPEN_API_DOCUMENT);
        Long operationId = operationService.listOperations(connectorId).stream()
                .filter(operation -> "getOrders".equals(operation.getOperationKey()))
                .findFirst()
                .orElseThrow()
                .getId();
        operationService.publish(
                operationId, operationService.getOperation(operationId).getVersion());

        toolId = toolService.create(
                new AiToolSaveDTO().setCode(TOOL_CODE).setName("查询订单").setConnectorId(connectorId));
        Long versionId = toolService.createVersion(new AiToolVersionSaveDTO()
                .setToolId(toolId)
                .setToolType("READ")
                .setPolicy("CONFIRM")
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef("getOrders")
                .setInputSchemaJson("{\"region\":{\"type\":\"string\",\"required\":true}}")
                .setOutputSchemaJson("{\"columns\":[]}"));
        toolService.publishVersion(versionId, toolService.getVersion(versionId).getVersion());

        cleanChain();
        // 运行行与其 FK 链（应用/服务/发布/会话）直接落库：本卡只关心动作与步骤调度，
        // 受理链路本身由 O01/O04 的证据覆盖；这里保证 ai_run 的外键完整、状态可控。
        jdbcTemplate.update(
                "INSERT INTO ai_application (app_code, name, origins, enabled, creator, updater)"
                        + " VALUES (?, 'IT 确认应用', '[]', b'1', 'it', 'it')",
                APP_CODE);
        applicationId =
                jdbcTemplate.queryForObject("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        jdbcTemplate.update(
                "INSERT INTO ai_service (app_id, code, name, status, model_endpoint_id, prompt_template,"
                        + " input_schema, required_capabilities, run_subject_type, creator, updater)"
                        + " VALUES (?, 'it-action-service', 'IT 确认服务', 'READY', 1, 'prompt', '{}', 'TEXT',"
                        + " 'USER', 'it', 'it')",
                applicationId);
        serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_service WHERE code = 'it-action-service' AND app_id = ?", Long.class, applicationId);
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
                        + " VALUES (?, 'USER', ?, ?, 'IT 确认会话', '{}', 'it', 'it')",
                applicationId,
                USER,
                "conv_action_" + System.nanoTime());
        conversationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversation WHERE external_user_id = ? ORDER BY id DESC LIMIT 1", Long.class, USER);

        jdbcTemplate.update(
                "INSERT INTO ai_run (run_key, application_id, subject_type, external_user_id,"
                        + " conversation_id, service_id, release_id, model_endpoint_id, endpoint_config_revision,"
                        + " content_hash, input_digest, status, step_count, creator, updater)"
                        + " VALUES (?, ?, 'USER', ?, ?, ?, ?, 1, 1, ?, ?, 'RUNNING', 0, 'it', 'it')",
                "run_action_" + System.nanoTime(),
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

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_tool_action WHERE run_id = ?", runId);
        jdbcTemplate.update("DELETE FROM ai_run WHERE id = ?", runId);
        jdbcTemplate.update(
                "DELETE FROM ai_tool_version WHERE tool_id IN (SELECT id FROM ai_tool WHERE code = ?)", TOOL_CODE);
        jdbcTemplate.update("DELETE FROM ai_tool WHERE code = ?", TOOL_CODE);
        jdbcTemplate.update("DELETE FROM ai_connector_operation WHERE connector_id = ?", connectorId);
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code = ?", CONNECTOR_CODE);
        runId = null;
        toolId = null;
        connectorId = null;
    }

    /** 清理运行行与其 FK 链（应用/服务/发布/会话）：让 setup 可重复执行。 */
    private void cleanChain() {
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

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private AiAnalysisStepRequestDTO stepRequest() {
        return new AiAnalysisStepRequestDTO()
                .setRunId(runId)
                .setApplicationId(applicationId)
                .setSubjectType(SUBJECT_TYPE)
                .setExternalUserId(USER)
                .setToolCode(TOOL_CODE)
                .setArguments(Map.of("region", "EAST"));
    }

    private static void assertSameAction(Throwable throwable, ErrorCode expected) {
        assertCode(throwable, expected);
    }

    @Test
    void confirmBindsChallengeAndArgumentsThenExecutesOnce() {
        AiAnalysisStepResultDTO step = stepScheduler.executeStep(stepRequest());

        assertThat(step.getOutcome()).isEqualTo(AiAnalysisStepResultDTO.OUTCOME_AWAITING_CONFIRMATION);
        assertThat(step.getStepsUsed()).isEqualTo(1);
        assertThat(step.getChallenge()).hasSize(32);
        assertThat(jdbcTemplate.queryForObject("SELECT step_count FROM ai_run WHERE id = ?", Integer.class, runId))
                .isEqualTo(1);

        Long actionId = step.getActionId();
        // 改参数：拒绝并要求重新确认（AT-020）
        assertThatThrownBy(() -> actionService.confirm(
                        actionId, applicationId, SUBJECT_TYPE, USER, step.getChallenge(), Map.of("region", "WEST")))
                .satisfies(throwable ->
                        assertSameAction(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_ARGUMENTS_CHANGED));
        // 换用户：拒绝
        assertThatThrownBy(() -> actionService.confirm(
                        actionId,
                        applicationId,
                        SUBJECT_TYPE,
                        "other-user",
                        step.getChallenge(),
                        Map.of("region", "EAST")))
                .satisfies(throwable ->
                        assertSameAction(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_CHALLENGE_INVALID));
        // 错误挑战：拒绝
        assertThatThrownBy(() -> actionService.confirm(
                        actionId, applicationId, SUBJECT_TYPE, USER, "wrong-challenge", Map.of("region", "EAST")))
                .satisfies(throwable ->
                        assertSameAction(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_CHALLENGE_INVALID));

        // 正确确认 → CONFIRMED
        AiToolActionDO confirmed = actionService.confirm(
                actionId, applicationId, SUBJECT_TYPE, USER, step.getChallenge(), Map.of("region", "EAST"));
        assertThat(confirmed.getStatus()).isEqualTo(AiToolActionDO.STATUS_CONFIRMED);

        // 执行：出站策略拒绝（本机无允许清单目标）→ 动作落 FAILED + 稳定原因码，
        // 但"已执行过"的事实保留（重放不会产生第二次副作用）
        actionService.execute(actionId, applicationId, SUBJECT_TYPE, USER);
        AiToolActionDO afterExecute = actionService.getAction(actionId, applicationId, SUBJECT_TYPE, USER);
        assertThat(afterExecute.getStatus())
                .as("resultCode=%s, version=%s", afterExecute.getResultCode(), afterExecute.getVersion())
                .isEqualTo(AiToolActionDO.STATUS_FAILED);
        assertThat(afterExecute.getResultCode()).isNotBlank();

        // 重放：不再是 CONFIRMED → 拒绝（不产生第二次副作用，AT-021）
        assertThatThrownBy(() -> actionService.execute(actionId, applicationId, SUBJECT_TYPE, USER))
                .satisfies(throwable -> assertSameAction(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_NOT_PENDING));
    }

    @Test
    void expiredActionCannotBeConfirmed() {
        AiAnalysisStepResultDTO step = stepScheduler.executeStep(stepRequest());
        jdbcTemplate.update(
                "UPDATE ai_tool_action SET expires_at = ? WHERE id = ?",
                java.time.LocalDateTime.now().minusMinutes(1),
                step.getActionId());

        assertThatThrownBy(() -> actionService.confirm(
                        step.getActionId(),
                        applicationId,
                        SUBJECT_TYPE,
                        USER,
                        step.getChallenge(),
                        Map.of("region", "EAST")))
                .satisfies(throwable -> assertSameAction(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_EXPIRED));
    }

    @Test
    void cancelledRunRefusesFurtherSteps() {
        stepScheduler.executeStep(stepRequest());
        jdbcTemplate.update("UPDATE ai_run SET status = 'CANCELLED' WHERE id = ?", runId);

        assertThatThrownBy(() -> stepScheduler.executeStep(stepRequest()))
                .as("取消后不再进入后续工具（AT-016）")
                .satisfies(throwable -> assertSameAction(throwable, AiErrorCodeConstants.AI_RUN_NOT_ACTIVE));
        assertThat(jdbcTemplate.queryForObject("SELECT step_count FROM ai_run WHERE id = ?", Integer.class, runId))
                .as("取消后步数不再增长")
                .isEqualTo(1);
    }

    @Test
    void concurrentConfirmationHasSingleWinner() throws Exception {
        AiAnalysisStepResultDTO step = stepScheduler.executeStep(stepRequest());
        Long actionId = step.getActionId();
        String challenge = step.getChallenge();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> confirm = () -> {
                try {
                    return actionService.confirm(
                            actionId, applicationId, SUBJECT_TYPE, USER, challenge, Map.of("region", "EAST"));
                } catch (Throwable failure) {
                    return failure;
                }
            };
            List<Future<Object>> futures = List.of(pool.submit(confirm), pool.submit(confirm));
            long successes = 0;
            for (Future<Object> future : futures) {
                if (future.get() instanceof AiToolActionDO) {
                    successes++;
                }
            }
            assertThat(successes).as("并发确认只有一个赢家").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(actionService
                        .getAction(actionId, applicationId, SUBJECT_TYPE, USER)
                        .getStatus())
                .isEqualTo(AiToolActionDO.STATUS_CONFIRMED);
    }
}
