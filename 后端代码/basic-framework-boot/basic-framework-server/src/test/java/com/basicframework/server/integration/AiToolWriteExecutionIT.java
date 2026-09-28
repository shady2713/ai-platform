package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepResultDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * X06 受控写工具的**执行**闭环（真实 MySQL + 真实业务系统模拟器）。
 *
 * <p>覆盖：写版本的发布闸门（必须声明业务幂等键与核对查询、政策不得 AUTO、读工具不得声明写绑定）；
 * 步骤调度只生成待确认动作；确认后执行一次（业务幂等键真的发给上游、上游按同键不重复入账）；
 * 重复确认/重放执行不产生第二次副作用（AT-021）；同一业务键的不同参数被拒绝；
 * 未授权目标被受控出站边界拒绝 = 确定未生效（FAILED）。
 */
@Import(AiToolWriteAcceptanceSupport.WriteSimulatorConfiguration.class)
class AiToolWriteExecutionIT extends AiToolWriteAcceptanceSupport {

    private static final Map<String, Object> ARGUMENTS = Map.of("payment_no", "P-1", "amount", 100);

    @Test
    void writePublishRequiresIdempotencyBindingAndRejectsAutoPolicy() {
        // AUTO 写版本：写调用必须人工确认，不能发布
        Long autoVersion = createDraftVersion(writeToolId, "WRITE", "AUTO", WRITE_BINDING_OUTPUT_SCHEMA);
        assertThatThrownBy(() -> toolService.publishVersion(autoVersion, 0))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_POLICY_UNSUPPORTED));

        // 缺声明：写工具必须登记业务幂等键与核对查询
        Long noBinding = createDraftVersion(writeToolId, "WRITE", "CONFIRM", "{\"columns\":[]}");
        assertThatThrownBy(() -> toolService.publishVersion(noBinding, 0))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));

        // 核对查询没声明业务键参数（getPayment 未发布时不可用作核对证据）
        jdbcTemplate.update(
                "UPDATE ai_connector_operation SET status = 'DRAFT' WHERE connector_id = ? AND operation_key = ?",
                connectorId,
                "getPayment");
        Long withDraftVerify = createDraftVersion(writeToolId, "WRITE", "CONFIRM", WRITE_BINDING_OUTPUT_SCHEMA);
        assertThatThrownBy(() -> toolService.publishVersion(withDraftVerify, 0))
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED));
        jdbcTemplate.update(
                "UPDATE ai_connector_operation SET status = 'PUBLISHED' WHERE connector_id = ? AND operation_key = ?",
                connectorId,
                "getPayment");

        // 读工具不得声明写绑定（语义矛盾）
        Long readWithBinding = createDraftVersion(readToolId, "READ", "AUTO", WRITE_BINDING_OUTPUT_SCHEMA);
        assertThatThrownBy(() -> toolService.publishVersion(readWithBinding, 0))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));

        // 读工具的正常发布路径不受影响（夹具里的读版本已发布）
        assertThat(toolService.getVersion(readVersionId).getStatus()).isEqualTo(AiToolVersionDO.STATUS_PUBLISHED);
    }

    @Test
    void writeStepCreatesPendingActionWithFrozenBusinessKeyAndReusesSameIntent() {
        AiAnalysisStepResultDTO step = stepScheduler.executeStep(writeStepRequest(ARGUMENTS));

        assertThat(step.getOutcome()).isEqualTo(AiAnalysisStepResultDTO.OUTCOME_AWAITING_CONFIRMATION);
        assertThat(step.getActionStatus()).isEqualTo(AiToolActionDO.STATUS_PENDING);
        assertThat(step.getChallenge()).hasSize(32);
        assertThat(jdbcTemplate.queryForObject("SELECT step_count FROM ai_run WHERE id = ?", Integer.class, runId))
                .isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT tool_type, idempotency_param, idempotency_key, verify_source_ref, verify_param,"
                        + " attempt_epoch FROM ai_tool_action WHERE id = ?",
                step.getActionId());
        assertThat(row.get("tool_type")).isEqualTo("WRITE");
        assertThat(row.get("idempotency_param")).isEqualTo("payment_no");
        assertThat(row.get("idempotency_key")).isEqualTo("P-1");
        assertThat(row.get("verify_source_ref")).isEqualTo("getPayment");
        assertThat(row.get("verify_param")).isEqualTo("payment_no");
        assertThat(row.get("attempt_epoch")).isEqualTo(0);

        // 同一业务意图再次发起：复用同一条动作，不重新发挑战（也绝不产生第二个动作）
        AiAnalysisStepResultDTO again = stepScheduler.executeStep(writeStepRequest(ARGUMENTS));
        assertThat(again.getOutcome()).isEqualTo(AiAnalysisStepResultDTO.OUTCOME_IDEMPOTENT_REUSE);
        assertThat(again.getActionId()).isEqualTo(step.getActionId());
        assertThat(again.getChallenge()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_tool_action WHERE tool_id = ? AND idempotency_key = 'P-1'",
                        Integer.class,
                        writeToolId))
                .isEqualTo(1);

        // 同键不同参数：拒绝（不产生第二个动作）
        assertThatThrownBy(
                        () -> stepScheduler.executeStep(writeStepRequest(Map.of("payment_no", "P-1", "amount", 999))))
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void registryJudgementBlocksUnpublishedOrDisabledWriteTools() {
        // 草稿版本不能执行：未审核的写工具一律拒绝
        Long draft = createDraftVersion(writeToolId, "WRITE", "CONFIRM", WRITE_BINDING_OUTPUT_SCHEMA);
        assertThat(toolService.getVersion(draft).getStatus()).isEqualTo(AiToolVersionDO.STATUS_DRAFT);

        // 停用工具后无法执行（与"不存在"同码，不泄漏存在性）
        AiToolDO tool = toolService.getTool(writeToolId);
        toolService.updateStatus(writeToolId, tool.getVersion(), false);
        assertThatThrownBy(() -> stepScheduler.executeStep(writeStepRequest(ARGUMENTS)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_NOT_FOUND));
        toolService.updateStatus(writeToolId, toolService.getTool(writeToolId).getVersion(), true);

        // 重新启用后可以继续（政策仍是 CONFIRM → 生成动作，不执行）
        assertThat(stepScheduler.executeStep(writeStepRequest(ARGUMENTS)).getOutcome())
                .isEqualTo(AiAnalysisStepResultDTO.OUTCOME_AWAITING_CONFIRMATION);
    }

    @Test
    void confirmedWriteExecutesOnceWithTheBusinessKeySentUpstream() {
        Long actionId = createAndConfirmAction(ARGUMENTS);

        AiToolActionDO executed = actionService.execute(actionId, applicationId, SUBJECT_TYPE, USER);

        assertThat(executed.getStatus()).isEqualTo(AiToolActionDO.STATUS_EXECUTED);
        assertThat(executed.getResultCode()).isEqualTo("COMPLETE");
        assertThat(executed.getAttemptEpoch()).isEqualTo(1);
        // 业务事实：模拟器真的入账一次，且收到的参数里带业务幂等键
        assertThat(simulator.applied("P-1")).isTrue();
        assertThat(simulator.appliedCount()).isEqualTo(1);
        assertThat(simulator.writeCalls()).isEqualTo(1);
        assertThat(simulator.receivedWrites()).hasSize(1);
        assertThat(simulator.receivedWrites().get(0)).containsEntry("payment_no", "P-1");

        // 重放执行：确认只被消费一次 → 不产生第二次副作用（AT-021）
        assertThatThrownBy(() -> actionService.execute(actionId, applicationId, SUBJECT_TYPE, USER))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_NOT_PENDING));
        assertThat(simulator.writeCalls()).as("重放不得再次外发写请求").isEqualTo(1);
        assertThat(simulator.appliedCount()).isEqualTo(1);
        assertThat(simulator.applied("P-1")).isTrue();
    }

    @Test
    void concurrentConfirmationHasASingleWinnerAndOnlyOneSideEffect() throws Exception {
        AiAnalysisStepResultDTO step = stepScheduler.executeStep(writeStepRequest(ARGUMENTS));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long winners;
        try {
            Callable<Object> confirm = () -> {
                try {
                    return actionService.confirm(
                            step.getActionId(), applicationId, SUBJECT_TYPE, USER, step.getChallenge(), ARGUMENTS);
                } catch (Throwable failure) {
                    return failure;
                }
            };
            List<Future<Object>> futures = List.of(pool.submit(confirm), pool.submit(confirm));
            winners = futures.stream()
                    .filter(future -> {
                        try {
                            return future.get() instanceof AiToolActionDO;
                        } catch (Exception failure) {
                            return false;
                        }
                    })
                    .count();
        } finally {
            pool.shutdownNow();
        }
        assertThat(winners).as("并发确认只有一个赢家").isEqualTo(1);

        actionService.execute(step.getActionId(), applicationId, SUBJECT_TYPE, USER);
        assertThat(simulator.appliedCount()).as("确认只被消费一次：业务系统只入账一次").isEqualTo(1);
    }

    @Test
    void tamperedArgumentsRejectedAndWriteBindingChangeRequiresReconfirmation() {
        AiAnalysisStepResultDTO step = stepScheduler.executeStep(writeStepRequest(ARGUMENTS));

        // 确认时改参数（AT-020）：拒绝并要求重新确认
        assertThatThrownBy(() -> actionService.confirm(
                        step.getActionId(),
                        applicationId,
                        SUBJECT_TYPE,
                        USER,
                        step.getChallenge(),
                        Map.of("payment_no", "P-1", "amount", 1)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_ARGUMENTS_CHANGED));

        actionService.confirm(step.getActionId(), applicationId, SUBJECT_TYPE, USER, step.getChallenge(), ARGUMENTS);

        // 确认后发布新版本把核对查询换掉：旧确认不能继续（写绑定变化与改参数同等）
        Long switchedVersion = createDraftVersion(
                writeToolId,
                "WRITE",
                "CONFIRM",
                """
                {"columns": [], "write": {"idempotencyParam": "payment_no",
                                          "reconcileOperation": "getPaymentOther"}}
                """);
        publish(switchedVersion);

        assertThatThrownBy(() -> actionService.execute(step.getActionId(), applicationId, SUBJECT_TYPE, USER))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_CHANGED));
        assertThat(simulator.writeCalls()).as("绑定变化后不得外发写请求").isZero();
    }

    @Test
    void blockedOutboundTargetIsADefiniteFailureAndNeverReachesAnyBusinessSystem() {
        Long blockedToolId = toolService.create(new AiToolSaveDTO()
                .setCode(BLOCKED_WRITE_TOOL_CODE)
                .setName("未授权目标收款")
                .setConnectorId(blockedConnectorId));
        Long versionId =
                toolService.createVersion(new com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO()
                        .setToolId(blockedToolId)
                        .setToolType("WRITE")
                        .setPolicy("CONFIRM")
                        .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                        .setSourceRef("createPayment")
                        .setInputSchemaJson(INPUT_SCHEMA)
                        .setOutputSchemaJson(WRITE_BINDING_OUTPUT_SCHEMA));
        publish(versionId);

        AiAnalysisStepResultDTO step = stepScheduler.executeStep(
                new com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepRequestDTO()
                        .setRunId(runId)
                        .setApplicationId(applicationId)
                        .setSubjectType(SUBJECT_TYPE)
                        .setExternalUserId(USER)
                        .setToolCode(BLOCKED_WRITE_TOOL_CODE)
                        .setArguments(ARGUMENTS));
        actionService.confirm(step.getActionId(), applicationId, SUBJECT_TYPE, USER, step.getChallenge(), ARGUMENTS);

        AiToolActionDO attempted = actionService.execute(step.getActionId(), applicationId, SUBJECT_TYPE, USER);

        // 出站策略在发送前拒绝：确定没有副作用 → FAILED（不是 UNKNOWN）
        assertThat(attempted.getStatus()).isEqualTo(AiToolActionDO.STATUS_FAILED);
        assertThat(attempted.getResultCode()).isEqualTo("TARGET_NOT_ALLOWED");
        assertThat(simulator.writeCalls()).as("未授权目标不会到达任何业务系统").isZero();
        assertThat(simulator.appliedCount()).isZero();
    }

    @Test
    void readToolsCannotBeExecutedThroughTheWriteEntryAndWriteToolsNotThroughTheGenericEntry() {
        AiToolVersionDO writeVersion = toolService.getVersion(writeVersionId);
        AiToolDecision writeDecision = new AiToolDecision(
                AiToolDecision.Outcome.EXECUTE, writeVersion, connectorId, "createPayment", ARGUMENTS);
        assertThatThrownBy(() -> toolExecutor.execute(writeDecision))
                .as("写工具判定不能走通用执行入口")
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_REQUIRES_CONFIRMATION));
        assertThat(simulator.writeCalls()).isZero();

        // 通用入口（读/自动路径）对读判定仍然可用：读调用没有副作用，不需要业务幂等键
        AiToolDecision readDecision = new AiToolDecision(
                AiToolDecision.Outcome.EXECUTE,
                toolService.getVersion(readVersionId),
                connectorId,
                "getPayment",
                Map.of("payment_no", "P-1"));
        assertThat(toolExecutor.execute(readDecision).getStatus()).isEqualTo("COMPLETE");
        assertThat(simulator.reconcileCalls()).isEqualTo(1);
    }
}
