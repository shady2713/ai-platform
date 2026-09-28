package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.tool.action.dto.AiAnalysisStepResultDTO;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * X06 写调用"结果未定"与核对闭环（真实 MySQL + 业务系统模拟器故障注入，AT-019）。
 *
 * <p>覆盖：入账后超时/连接中断/5xx → UNKNOWN（不谎报失败、不自动重放）；程序核对按业务键查到真实
 * 业务事实后收敛为 EXECUTED；未生效收敛为 FAILED 且允许业务重新发起；进程崩溃留下的 EXECUTING
 * 由核对解决；核对查询本身不可用时动作保持未定；人工核对记录结论与说明；并发核对只有一个赢家；
 * 终态不可被核对改写（核对不是改写历史的通道）。
 */
@Import(AiToolWriteAcceptanceSupport.WriteSimulatorConfiguration.class)
class AiToolWriteReconcileIT extends AiToolWriteAcceptanceSupport {

    private static final Map<String, Object> ARGUMENTS = Map.of("payment_no", "P-1", "amount", 100);

    @Test
    void timeoutAfterApplicationIsUnknownAndOnlyProgramReconcileProvesTheRealEffect() {
        simulator.setFault(AiBusinessWriteSimulator.Fault.APPLIED_THEN_TIMEOUT);
        Long actionId = createAndConfirmAction(ARGUMENTS);

        AiToolActionDO attempted = actionService.execute(actionId, applicationId, SUBJECT_TYPE, USER);

        assertThat(attempted.getStatus()).as("上游读超时：结果未定，绝不记成失败").isEqualTo(AiToolActionDO.STATUS_UNKNOWN);
        assertThat(attempted.getResultCode()).isEqualTo("TIMEOUT");
        assertThat(attempted.getAttemptEpoch()).isEqualTo(1);
        // 业务事实：其实已经入账了（这正是不能盲目重放的原因）
        assertThat(simulator.applied("P-1")).isTrue();
        assertThat(simulator.appliedCount()).isEqualTo(1);

        // 不允许自动重放：确认已被消费，execute 不再可用
        assertThatThrownBy(() -> actionService.execute(actionId, applicationId, SUBJECT_TYPE, USER))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_NOT_PENDING));
        assertThat(simulator.writeCalls()).isEqualTo(1);

        // 程序核对：调用登记的核对查询（按业务幂等键），查到的业务事实决定结论
        AiToolActionDO reconciled =
                actionService.reconcile(actionId, applicationId, SUBJECT_TYPE, USER, "PROGRAM", null, null);
        assertThat(reconciled.getStatus()).isEqualTo(AiToolActionDO.STATUS_EXECUTED);
        assertThat(reconciled.getVerifiedBy()).isEqualTo(AiToolActionDO.VERIFIED_BY_PROGRAM);
        assertThat(reconciled.getVerifyResult()).isEqualTo(AiToolActionDO.VERIFY_APPLIED);
        assertThat(reconciled.getVerifyEvidence()).isEqualTo("getPayment:items=1");
        assertThat(reconciled.getResultCode()).isEqualTo("reconciled-applied");
        assertThat(reconciled.getVerifiedAt()).isNotNull();
        assertThat(simulator.reconcileCalls()).isEqualTo(1);
        assertThat(simulator.appliedCount()).as("核对不产生第二次副作用").isEqualTo(1);

        // 终态不可再被核对改写
        assertThatThrownBy(() -> actionService.reconcile(
                        actionId, applicationId, SUBJECT_TYPE, USER, "MANUAL", "NOT_APPLIED", null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_NOT_RECONCILABLE));
    }

    @Test
    void notAppliedOutcomeIsFailedAndTheBusinessIntentCanBeRestarted() {
        simulator.setFault(AiBusinessWriteSimulator.Fault.TIMEOUT_BEFORE_APPLY);
        Long actionId = createAndConfirmAction(ARGUMENTS);
        assertThat(actionService
                        .execute(actionId, applicationId, SUBJECT_TYPE, USER)
                        .getStatus())
                .isEqualTo(AiToolActionDO.STATUS_UNKNOWN);
        assertThat(simulator.applied("P-1")).isFalse();

        AiToolActionDO reconciled =
                actionService.reconcile(actionId, applicationId, SUBJECT_TYPE, USER, "PROGRAM", null, null);
        assertThat(reconciled.getStatus()).as("业务未生效：如实记失败（不猜成功）").isEqualTo(AiToolActionDO.STATUS_FAILED);
        assertThat(reconciled.getVerifyResult()).isEqualTo(AiToolActionDO.VERIFY_NOT_APPLIED);
        assertThat(reconciled.getVerifyEvidence()).isEqualTo("getPayment:items=0");
        assertThat(reconciled.getResultCode()).isEqualTo("reconciled-not-applied");

        // 明确未生效后，同一业务键可以重新发起（失败/过期/取消不永久占键），且这次真的入账
        simulator.setFault(AiBusinessWriteSimulator.Fault.NONE);
        Long retriedActionId = createAndConfirmAction(ARGUMENTS);
        assertThat(retriedActionId).isNotEqualTo(actionId);
        assertThat(actionService
                        .execute(retriedActionId, applicationId, SUBJECT_TYPE, USER)
                        .getStatus())
                .isEqualTo(AiToolActionDO.STATUS_EXECUTED);
        assertThat(simulator.appliedCount()).as("重新发起恰好一次入账").isEqualTo(1);
        assertThat(simulator.applied("P-1")).isTrue();
    }

    @Test
    void crashedAttemptIsResolvedByReconcileAgainstRealBusinessFact() {
        // 业务系统里已有该键的收款记录（崩溃前写其实已经生效）
        simulator.applyDirectly("P-1", 100);
        AiAnalysisStepResultDTO step = stepScheduler.executeStep(writeStepRequest(ARGUMENTS));
        // 模拟进程崩溃：确认已被消费（EXECUTING + 尝试代数 1），但结论没有落库
        jdbcTemplate.update(
                "UPDATE ai_tool_action SET status = 'EXECUTING', attempt_epoch = 1, executed_at = NOW()"
                        + " WHERE id = ?",
                step.getActionId());

        AiToolActionDO reconciled =
                actionService.reconcile(step.getActionId(), applicationId, SUBJECT_TYPE, USER, "PROGRAM", null, null);

        assertThat(reconciled.getStatus())
                .as("崩溃留下的 EXECUTING 由核对收敛到真实结果（已生效）")
                .isEqualTo(AiToolActionDO.STATUS_EXECUTED);
        assertThat(reconciled.getVerifyResult()).isEqualTo(AiToolActionDO.VERIFY_APPLIED);
        assertThat(reconciled.getAttemptEpoch()).isEqualTo(1);
        assertThat(simulator.appliedCount()).as("核对本身不产生副作用").isEqualTo(1);
    }

    @Test
    void reconcileQueryFailureKeepsTheActionUndetermined() {
        simulator.setFault(AiBusinessWriteSimulator.Fault.APPLIED_THEN_HTTP_500);
        Long actionId = createAndConfirmAction(ARGUMENTS);
        assertThat(actionService
                        .execute(actionId, applicationId, SUBJECT_TYPE, USER)
                        .getStatus())
                .as("上游 500 可能已生效：结果未定")
                .isEqualTo(AiToolActionDO.STATUS_UNKNOWN);

        // 核对查询本身 5xx：动作保持未定（502），不得猜结论
        simulator.setFault(AiBusinessWriteSimulator.Fault.RECONCILE_HTTP_500);
        assertThatThrownBy(() ->
                        actionService.reconcile(actionId, applicationId, SUBJECT_TYPE, USER, "PROGRAM", null, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_RECONCILE_FAILED));
        assertThat(actionService
                        .getAction(actionId, applicationId, SUBJECT_TYPE, USER)
                        .getStatus())
                .isEqualTo(AiToolActionDO.STATUS_UNKNOWN);

        // 核对查询恢复后可以再次核对（结果未定的动作允许重复核对，直到收敛）
        simulator.setFault(AiBusinessWriteSimulator.Fault.NONE);
        AiToolActionDO reconciled =
                actionService.reconcile(actionId, applicationId, SUBJECT_TYPE, USER, "PROGRAM", null, null);
        assertThat(reconciled.getStatus()).isEqualTo(AiToolActionDO.STATUS_EXECUTED);
        assertThat(reconciled.getVerifyResult()).isEqualTo(AiToolActionDO.VERIFY_APPLIED);
    }

    @Test
    void manualReconcileRecordsOperatorConclusionAndValidatesInput() {
        simulator.setFault(AiBusinessWriteSimulator.Fault.APPLIED_THEN_RESET);
        Long actionId = createAndConfirmAction(ARGUMENTS);
        assertThat(actionService
                        .execute(actionId, applicationId, SUBJECT_TYPE, USER)
                        .getStatus())
                .as("连接中断：结果未定")
                .isEqualTo(AiToolActionDO.STATUS_UNKNOWN);

        assertThatThrownBy(() ->
                        actionService.reconcile(actionId, applicationId, SUBJECT_TYPE, USER, "MANUAL", "MAYBE", null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> actionService.reconcile(
                        actionId, applicationId, SUBJECT_TYPE, USER, "SOMETIME", "APPLIED", null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> actionService.reconcile(
                        actionId, applicationId, SUBJECT_TYPE, USER, "MANUAL", "APPLIED", "x".repeat(201)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));

        AiToolActionDO reconciled = actionService.reconcile(
                actionId, applicationId, SUBJECT_TYPE, USER, "MANUAL", "APPLIED", "已与业务方核对：收款已入账");
        assertThat(reconciled.getStatus()).isEqualTo(AiToolActionDO.STATUS_EXECUTED);
        assertThat(reconciled.getVerifiedBy()).isEqualTo(AiToolActionDO.VERIFIED_BY_MANUAL);
        assertThat(reconciled.getVerifyEvidence()).isEqualTo("已与业务方核对：收款已入账");
        assertThat(simulator.reconcileCalls()).as("人工核对不调用核对查询").isZero();

        // 越权与不存在同语义：别人的主体核对不了
        assertThatThrownBy(() -> actionService.reconcile(
                        actionId, applicationId, SUBJECT_TYPE, "other-user", "MANUAL", "APPLIED", null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_CHALLENGE_INVALID));
    }

    @Test
    void concurrentReconcileHasASingleWinner() throws Exception {
        simulator.setFault(AiBusinessWriteSimulator.Fault.APPLIED_THEN_TIMEOUT);
        Long actionId = createAndConfirmAction(ARGUMENTS);
        actionService.execute(actionId, applicationId, SUBJECT_TYPE, USER);
        simulator.setFault(AiBusinessWriteSimulator.Fault.NONE);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        long winners;
        try {
            Callable<Object> reconcile = () -> {
                try {
                    return actionService.reconcile(actionId, applicationId, SUBJECT_TYPE, USER, "PROGRAM", null, null);
                } catch (Throwable failure) {
                    return failure;
                }
            };
            List<Future<Object>> futures = List.of(pool.submit(reconcile), pool.submit(reconcile));
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
        assertThat(winners).as("并发核对只有一个赢家").isEqualTo(1);
        assertThat(actionService
                        .getAction(actionId, applicationId, SUBJECT_TYPE, USER)
                        .getStatus())
                .isEqualTo(AiToolActionDO.STATUS_EXECUTED);
    }

    @Test
    void expiredConfirmationProducesNoSideEffectAndTheIntentCanBeRestarted() {
        AiAnalysisStepResultDTO step = stepScheduler.executeStep(writeStepRequest(ARGUMENTS));
        jdbcTemplate.update(
                "UPDATE ai_tool_action SET expires_at = ? WHERE id = ?",
                java.time.LocalDateTime.now().minusMinutes(1),
                step.getActionId());

        // 过期确认：不执行（AT-021）
        assertThatThrownBy(() -> actionService.confirm(
                        step.getActionId(), applicationId, SUBJECT_TYPE, USER, step.getChallenge(), ARGUMENTS))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_EXPIRED));
        assertThat(simulator.writeCalls()).isZero();

        // 过期动作不占业务键：同一业务意图可以重新发起（新动作 + 新挑战），并真的执行一次
        Long restarted = createAndConfirmAction(ARGUMENTS);
        assertThat(restarted).isNotEqualTo(step.getActionId());
        assertThat(actionService
                        .execute(restarted, applicationId, SUBJECT_TYPE, USER)
                        .getStatus())
                .isEqualTo(AiToolActionDO.STATUS_EXECUTED);
        assertThat(simulator.appliedCount()).as("过期确认 + 重新发起 = 恰好一次副作用").isEqualTo(1);
    }
}
