package com.basicframework.module.ai.service.tool.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.action.AiToolActionMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolVersionMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.AiToolExecutor;
import com.basicframework.module.ai.service.tool.AiToolPolicyGate;
import com.basicframework.module.ai.service.tool.AiToolService;
import com.basicframework.module.ai.service.tool.AiToolWriteGate;
import com.basicframework.module.ai.service.tool.action.dto.AiToolActionCreateResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** D09 确认状态机：参数篡改、换用户、过期、重复确认与重放执行（AT-020/021）。 */
class AiToolActionServiceTest {

    private static final Long ACTION_ID = 501L;

    private static final Long RUN_ID = 401L;

    private static final Long APPLICATION_ID = 11L;

    private static final String SUBJECT_TYPE = "USER";

    private static final String EXTERNAL_USER = "u-001";

    private static final String TOOL_CODE = "query-orders";

    private static final Map<String, Object> ARGUMENTS = Map.of("region", "EAST");

    private final AiToolActionMapper actionMapper = mock(AiToolActionMapper.class);

    private final AiToolPolicyGate policyGate = mock(AiToolPolicyGate.class);

    private final AiToolExecutor toolExecutor = mock(AiToolExecutor.class);

    private final AiToolService toolService = mock(AiToolService.class);

    private final AiToolMapper toolMapper = mock(AiToolMapper.class);

    private final AiToolVersionMapper versionMapper = mock(AiToolVersionMapper.class);

    private final AiToolWriteGate writeGate = mock(AiToolWriteGate.class);

    private final AiToolActionServiceImpl service =
            new AiToolActionServiceImpl(actionMapper, policyGate, toolExecutor, toolService, writeGate);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiToolVersionDO version(String policy) {
        return new AiToolVersionDO()
                .setId(101L)
                .setToolId(91L)
                .setVersionNo(1)
                .setStatus(AiToolVersionDO.STATUS_PUBLISHED)
                .setToolType("READ")
                .setPolicy(policy)
                .setSourceRef("getOrders")
                .setInputSchemaJson("{\"region\":{\"type\":\"string\",\"required\":true}}")
                .setOutputSchemaJson("{\"columns\":[]}")
                .setSchemaHash("a".repeat(64))
                .setVersion(1);
    }

    private static AiToolActionDO action(String status, String argumentsHash, LocalDateTime expiresAt) {
        return new AiToolActionDO()
                .setId(ACTION_ID)
                .setRunId(RUN_ID)
                .setToolId(91L)
                .setToolVersionId(101L)
                .setApplicationId(APPLICATION_ID)
                .setSubjectType(SUBJECT_TYPE)
                .setExternalUserId(EXTERNAL_USER)
                .setPolicy("CONFIRM")
                .setArgumentsHash(argumentsHash)
                .setArgumentsJson("{\"region\":\"EAST\"}")
                .setChallenge("c0ffee".repeat(5) + "c0ff")
                .setStatus(status)
                .setExpiresAt(expiresAt)
                .setVersion(2);
    }

    @BeforeEach
    void setUp() {
        when(toolService.getTool(91L))
                .thenReturn(new AiToolDO()
                        .setId(91L)
                        .setCode(TOOL_CODE)
                        .setConnectorId(71L)
                        .setVersion(1));
        when(toolService.requirePublishedVersion(TOOL_CODE)).thenReturn(version("CONFIRM"));
    }

    @Test
    void confirmRequiresSameSubjectSameChallengeSameArgumentsAndFreshness() {
        String hash = AiToolActionStateMachine.argumentsHash(ARGUMENTS);
        LocalDateTime future = LocalDateTime.now().plusMinutes(5);
        when(actionMapper.selectById(ACTION_ID)).thenReturn(action(AiToolActionDO.STATUS_PENDING, hash, future));
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(1);
        when(actionMapper.selectById(ACTION_ID)).thenReturn(action(AiToolActionDO.STATUS_PENDING, hash, future));

        // 正常确认
        service.confirm(
                ACTION_ID,
                APPLICATION_ID,
                SUBJECT_TYPE,
                EXTERNAL_USER,
                action("PENDING", hash, future).getChallenge(),
                ARGUMENTS);

        // 换用户
        assertThatThrownBy(() -> service.confirm(
                        ACTION_ID,
                        APPLICATION_ID,
                        SUBJECT_TYPE,
                        "u-002",
                        action("PENDING", hash, future).getChallenge(),
                        ARGUMENTS))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_CHALLENGE_INVALID));

        // 挑战不符
        assertThatThrownBy(() ->
                        service.confirm(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "wrong", ARGUMENTS))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_CHALLENGE_INVALID));

        // 改参数（AT-020）：参数哈希不一致 → 拒绝并要求重新确认
        assertThatThrownBy(() -> service.confirm(
                        ACTION_ID,
                        APPLICATION_ID,
                        SUBJECT_TYPE,
                        EXTERNAL_USER,
                        action("PENDING", hash, future).getChallenge(),
                        Map.of("region", "WEST")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_ARGUMENTS_CHANGED));

        // 过期（AT-021）
        LocalDateTime past = LocalDateTime.now().minusMinutes(1);
        when(actionMapper.selectById(ACTION_ID)).thenReturn(action(AiToolActionDO.STATUS_PENDING, hash, past));
        assertThatThrownBy(() -> service.confirm(
                        ACTION_ID,
                        APPLICATION_ID,
                        SUBJECT_TYPE,
                        EXTERNAL_USER,
                        action("PENDING", hash, past).getChallenge(),
                        ARGUMENTS))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_EXPIRED));

        // 非 PENDING（已确认过）不可再确认
        when(actionMapper.selectById(ACTION_ID)).thenReturn(action(AiToolActionDO.STATUS_CONFIRMED, hash, future));
        assertThatThrownBy(() -> service.confirm(
                        ACTION_ID,
                        APPLICATION_ID,
                        SUBJECT_TYPE,
                        EXTERNAL_USER,
                        action("PENDING", hash, future).getChallenge(),
                        ARGUMENTS))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_NOT_PENDING));

        // 政策改成 DENY 后旧确认不能继续
        when(actionMapper.selectById(ACTION_ID)).thenReturn(action(AiToolActionDO.STATUS_PENDING, hash, future));
        when(toolService.requirePublishedVersion(TOOL_CODE)).thenReturn(version("DENY"));
        assertThatThrownBy(() -> service.confirm(
                        ACTION_ID,
                        APPLICATION_ID,
                        SUBJECT_TYPE,
                        EXTERNAL_USER,
                        action("PENDING", hash, future).getChallenge(),
                        ARGUMENTS))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_NOT_PENDING));
    }

    @Test
    void executeHappensOnlyOnceAndReplayIsBlocked() {
        String hash = AiToolActionStateMachine.argumentsHash(ARGUMENTS);
        LocalDateTime future = LocalDateTime.now().plusMinutes(5);
        when(actionMapper.selectById(ACTION_ID)).thenReturn(action(AiToolActionDO.STATUS_CONFIRMED, hash, future));
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(1);
        when(policyGate.decideAfterConfirmation(TOOL_CODE, ARGUMENTS))
                .thenReturn(new AiToolDecision(
                        AiToolDecision.Outcome.EXECUTE, version("CONFIRM"), 71L, "getOrders", ARGUMENTS));
        when(toolExecutor.execute(any()))
                .thenReturn(new AiConnectorExecutionResultDTO()
                        .setStatus("COMPLETE")
                        .setStoppedReason("no-more-pages"));

        service.execute(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER);
        verify(toolExecutor, times(1)).execute(any());

        // 重放：CAS 失败（版本已推进）→ 不产生第二次副作用
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.execute(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));
        verify(toolExecutor, times(1)).execute(any());

        // 未确认的动作不能执行
        when(actionMapper.selectById(ACTION_ID)).thenReturn(action(AiToolActionDO.STATUS_PENDING, hash, future));
        assertThatThrownBy(() -> service.execute(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_NOT_PENDING));
        verify(toolExecutor, times(1)).execute(any());
    }

    @Test
    void creationFreezesArgumentsAndBindsExpiringChallenge() {
        doAnswer(invocation -> {
                    ((AiToolActionDO) invocation.getArgument(0)).setId(ACTION_ID);
                    return 1;
                })
                .when(actionMapper)
                .insert(any(AiToolActionDO.class));

        AiToolActionCreateResult created = service.createFromDecision(
                new AiToolDecision(AiToolDecision.Outcome.CONFIRM, version("CONFIRM"), 71L, "getOrders", ARGUMENTS),
                RUN_ID,
                APPLICATION_ID,
                SUBJECT_TYPE,
                EXTERNAL_USER);

        assertThat(created.reused()).isFalse();
        assertThat(created.action().getArgumentsHash()).isEqualTo(AiToolActionStateMachine.argumentsHash(ARGUMENTS));
        assertThat(created.action().getChallenge()).hasSize(AiToolActionStateMachine.CHALLENGE_LENGTH);
        assertThat(created.action().getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(created.action().getStatus()).isEqualTo(AiToolActionDO.STATUS_PENDING);
        assertThat(created.action().getExternalUserId()).isEqualTo(EXTERNAL_USER);
        assertThat(created.action().getToolType()).isEqualTo(AiToolActionDO.TOOL_TYPE_READ);

        // 缺少主体信息：入参非法
        assertThatThrownBy(() -> service.createFromDecision(
                        new AiToolDecision(
                                AiToolDecision.Outcome.CONFIRM, version("CONFIRM"), 71L, "getOrders", ARGUMENTS),
                        RUN_ID,
                        null,
                        SUBJECT_TYPE,
                        EXTERNAL_USER))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }

    @Test
    void rejectAndExpireAreTerminalAndOwnedLookupsHideOthersActions() {
        String hash = AiToolActionStateMachine.argumentsHash(ARGUMENTS);
        LocalDateTime future = LocalDateTime.now().plusMinutes(5);
        when(actionMapper.selectById(ACTION_ID)).thenReturn(action(AiToolActionDO.STATUS_PENDING, hash, future));
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(1);

        service.reject(
                ACTION_ID,
                APPLICATION_ID,
                SUBJECT_TYPE,
                EXTERNAL_USER,
                action("PENDING", hash, future).getChallenge());
        service.expire(ACTION_ID);

        // 别人的动作：按不存在处理（不泄漏存在性）
        assertThatThrownBy(() -> service.getAction(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, "other-user"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_CHALLENGE_INVALID));

        when(actionMapper.selectById(ACTION_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.getAction(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_NOT_FOUND));

        assertThatThrownBy(() -> service.getActionPage(APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, null, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThat(List.of(AiToolActionStateMachine.newChallenge()).get(0)).hasSize(32);
    }

    // ========== X06：写工具的业务幂等键、结果未定与核对 ==========

    private static final String WRITE_TOOL_CODE = "create-payment";

    private static final Map<String, Object> WRITE_ARGUMENTS = Map.of("payment_no", "P-1", "amount", 10);

    private static AiToolVersionDO writeVersion() {
        return new AiToolVersionDO()
                .setId(101L)
                .setToolId(91L)
                .setVersionNo(2)
                .setStatus(AiToolVersionDO.STATUS_PUBLISHED)
                .setToolType("WRITE")
                .setPolicy("CONFIRM")
                .setSourceRef("createPayment")
                .setInputSchemaJson("{\"payment_no\":{\"type\":\"string\",\"required\":true},"
                        + "\"amount\":{\"type\":\"number\",\"required\":true}}")
                .setOutputSchemaJson("{\"columns\":[],\"write\":{\"idempotencyParam\":\"payment_no\","
                        + "\"reconcileOperation\":\"getPayment\",\"reconcileParam\":\"payment_no\"}}")
                .setSchemaHash("b".repeat(64))
                .setVersion(1);
    }

    private static AiToolActionDO writeAction(String status) {
        return new AiToolActionDO()
                .setId(ACTION_ID)
                .setRunId(RUN_ID)
                .setToolId(91L)
                .setToolVersionId(101L)
                .setApplicationId(APPLICATION_ID)
                .setSubjectType(SUBJECT_TYPE)
                .setExternalUserId(EXTERNAL_USER)
                .setPolicy("CONFIRM")
                .setToolType(AiToolActionDO.TOOL_TYPE_WRITE)
                .setArgumentsHash(AiToolActionStateMachine.argumentsHash(WRITE_ARGUMENTS))
                .setArgumentsJson("{\"amount\":10,\"payment_no\":\"P-1\"}")
                .setIdempotencyParam("payment_no")
                .setIdempotencyKey("P-1")
                .setVerifySourceRef("getPayment")
                .setVerifyParam("payment_no")
                .setChallenge("a1b2".repeat(8))
                .setStatus(status)
                .setAttemptEpoch(1)
                .setExpiresAt(LocalDateTime.now().plusMinutes(5))
                .setVersion(3);
    }

    private void mockWriteTool() {
        when(toolService.getTool(91L))
                .thenReturn(new AiToolDO()
                        .setId(91L)
                        .setCode(WRITE_TOOL_CODE)
                        .setConnectorId(71L)
                        .setVersion(1));
        when(toolService.requirePublishedVersion(WRITE_TOOL_CODE)).thenReturn(writeVersion());
    }

    @Test
    void writeActionFreezesBusinessKeyAndReusesExistingIntent() {
        doAnswer(invocation -> {
                    ((AiToolActionDO) invocation.getArgument(0)).setId(ACTION_ID);
                    return 1;
                })
                .when(actionMapper)
                .insert(any(AiToolActionDO.class));

        AiToolActionCreateResult created = service.createFromDecision(
                new AiToolDecision(
                        AiToolDecision.Outcome.CONFIRM, writeVersion(), 71L, "createPayment", WRITE_ARGUMENTS),
                RUN_ID,
                APPLICATION_ID,
                SUBJECT_TYPE,
                EXTERNAL_USER);
        assertThat(created.reused()).isFalse();
        assertThat(created.action().getToolType()).isEqualTo(AiToolActionDO.TOOL_TYPE_WRITE);
        assertThat(created.action().getIdempotencyKey()).isEqualTo("P-1");
        assertThat(created.action().getIdempotencyParam()).isEqualTo("payment_no");
        assertThat(created.action().getVerifySourceRef()).isEqualTo("getPayment");
        assertThat(created.action().getVerifyParam()).isEqualTo("payment_no");

        // 同一业务意图再次提交：复用同一条动作（不产生第二个动作，也就不会有第二次副作用）
        when(actionMapper.selectByBusinessKey(91L, "P-1")).thenReturn(writeAction(AiToolActionDO.STATUS_CONFIRMED));
        AiToolActionCreateResult reused = service.createFromDecision(
                new AiToolDecision(
                        AiToolDecision.Outcome.CONFIRM, writeVersion(), 71L, "createPayment", WRITE_ARGUMENTS),
                RUN_ID,
                APPLICATION_ID,
                SUBJECT_TYPE,
                EXTERNAL_USER);
        assertThat(reused.reused()).isTrue();
        assertThat(reused.action().getId()).isEqualTo(ACTION_ID);

        // 同键不同参数：409（不产生第二个动作，也不泄漏他人动作内容）
        when(actionMapper.selectByBusinessKey(91L, "P-1"))
                .thenReturn(writeAction(AiToolActionDO.STATUS_CONFIRMED)
                        .setArgumentsHash(AiToolActionStateMachine.argumentsHash(Map.of("payment_no", "P-1"))));
        assertThatThrownBy(() -> service.createFromDecision(
                        new AiToolDecision(
                                AiToolDecision.Outcome.CONFIRM, writeVersion(), 71L, "createPayment", WRITE_ARGUMENTS),
                        RUN_ID,
                        APPLICATION_ID,
                        SUBJECT_TYPE,
                        EXTERNAL_USER))
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_IDEMPOTENCY_CONFLICT));

        // 已过期但仍是待确认：先释放业务键（落 EXPIRED），同一业务键可以重新发起
        when(actionMapper.selectByBusinessKey(91L, "P-1"))
                .thenReturn(writeAction(AiToolActionDO.STATUS_PENDING)
                        .setExpiresAt(LocalDateTime.now().minusMinutes(1)));
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(1);
        AiToolActionCreateResult restarted = service.createFromDecision(
                new AiToolDecision(
                        AiToolDecision.Outcome.CONFIRM, writeVersion(), 71L, "createPayment", WRITE_ARGUMENTS),
                RUN_ID,
                APPLICATION_ID,
                SUBJECT_TYPE,
                EXTERNAL_USER);
        assertThat(restarted.reused()).isFalse();
        ArgumentCaptor<AiToolActionDO> expiry = ArgumentCaptor.forClass(AiToolActionDO.class);
        verify(actionMapper).updateWithVersion(expiry.capture(), any());
        assertThat(expiry.getValue().getStatus()).isEqualTo(AiToolActionDO.STATUS_EXPIRED);

        // 过期释放的 CAS 失败（状态被并发推进）：保守地当作仍占键 —— 复用，而不是插入第二个动作
        when(actionMapper.selectByBusinessKey(91L, "P-1"))
                .thenReturn(writeAction(AiToolActionDO.STATUS_PENDING)
                        .setExpiresAt(LocalDateTime.now().minusMinutes(1)));
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(0);
        assertThat(service.createFromDecision(
                                new AiToolDecision(
                                        AiToolDecision.Outcome.CONFIRM,
                                        writeVersion(),
                                        71L,
                                        "createPayment",
                                        WRITE_ARGUMENTS),
                                RUN_ID,
                                APPLICATION_ID,
                                SUBJECT_TYPE,
                                EXTERNAL_USER)
                        .reused())
                .isTrue();

        // 同键动作属于别的用户：同样拒绝，不返回他人动作
        when(actionMapper.selectByBusinessKey(91L, "P-1"))
                .thenReturn(writeAction(AiToolActionDO.STATUS_CONFIRMED).setExternalUserId("other-user"));
        assertThatThrownBy(() -> service.createFromDecision(
                        new AiToolDecision(
                                AiToolDecision.Outcome.CONFIRM, writeVersion(), 71L, "createPayment", WRITE_ARGUMENTS),
                        RUN_ID,
                        APPLICATION_ID,
                        SUBJECT_TYPE,
                        EXTERNAL_USER))
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void writeExecutionConsumesConfirmationOnceAndRecordsUnknownWithoutReplay() {
        mockWriteTool();
        when(actionMapper.selectById(ACTION_ID)).thenReturn(writeAction(AiToolActionDO.STATUS_CONFIRMED));
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(1);
        when(policyGate.decideAfterConfirmation(WRITE_TOOL_CODE, WRITE_ARGUMENTS))
                .thenReturn(new AiToolDecision(
                        AiToolDecision.Outcome.EXECUTE, writeVersion(), 71L, "createPayment", WRITE_ARGUMENTS));
        when(writeGate.requireExecutableBinding(any(), any(), any(), any())).thenReturn(null);
        // 上游读超时：结果未定
        when(toolExecutor.executeWrite(any(), any()))
                .thenReturn(new AiConnectorExecutionResultDTO()
                        .setStatus("FAILED")
                        .setDetailCode("TIMEOUT")
                        .setStoppedReason("upstream-failed"));

        AiToolActionDO executed = service.execute(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER);

        ArgumentCaptor<AiToolActionDO> updates = ArgumentCaptor.forClass(AiToolActionDO.class);
        verify(actionMapper, times(2)).updateWithVersion(updates.capture(), any());
        assertThat(updates.getAllValues().get(0).getStatus())
                .as("第一步只消费确认（EXECUTING + 尝试代数），不谎报成功")
                .isEqualTo(AiToolActionDO.STATUS_EXECUTING);
        assertThat(updates.getAllValues().get(0).getAttemptEpoch()).isEqualTo(2);
        assertThat(updates.getAllValues().get(1).getStatus()).isEqualTo(AiToolActionDO.STATUS_UNKNOWN);
        assertThat(updates.getAllValues().get(1).getResultCode()).isEqualTo("TIMEOUT");
        verify(toolExecutor, times(1)).executeWrite(any(), eq("P-1"));
        assertThat(executed).isNotNull();
    }

    @Test
    void writeExecutionMapsDefiniteRejectionsToFailedAndUnknownSurvivesReconcile() {
        mockWriteTool();
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(1);
        when(policyGate.decideAfterConfirmation(WRITE_TOOL_CODE, WRITE_ARGUMENTS))
                .thenReturn(new AiToolDecision(
                        AiToolDecision.Outcome.EXECUTE, writeVersion(), 71L, "createPayment", WRITE_ARGUMENTS));
        when(writeGate.requireExecutableBinding(any(), any(), any(), any())).thenReturn(null);

        // 明确拒绝（HTTP 400）：确定没有副作用 → FAILED
        when(actionMapper.selectById(ACTION_ID)).thenReturn(writeAction(AiToolActionDO.STATUS_CONFIRMED));
        when(toolExecutor.executeWrite(any(), any()))
                .thenReturn(
                        new AiConnectorExecutionResultDTO().setStatus("FAILED").setDetailCode("HTTP_400"));
        service.execute(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER);
        ArgumentCaptor<AiToolActionDO> updates = ArgumentCaptor.forClass(AiToolActionDO.class);
        verify(actionMapper, times(2)).updateWithVersion(updates.capture(), any());
        assertThat(updates.getAllValues().get(1).getStatus()).isEqualTo(AiToolActionDO.STATUS_FAILED);
        assertThat(updates.getAllValues().get(1).getResultCode()).isEqualTo("HTTP_400");

        // 程序核对：登记的核对查询查到业务对象 → 已生效
        when(actionMapper.selectById(ACTION_ID)).thenReturn(writeAction(AiToolActionDO.STATUS_UNKNOWN));
        when(toolExecutor.executeReconcile(eq(71L), eq("getPayment"), eq(Map.of("payment_no", "P-1"))))
                .thenReturn(new AiConnectorExecutionResultDTO()
                        .setStatus("COMPLETE")
                        .setItemCount(1));
        service.reconcile(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "program", null, null);
        ArgumentCaptor<AiToolActionDO> reconcileUpdates = ArgumentCaptor.forClass(AiToolActionDO.class);
        verify(actionMapper, times(3)).updateWithVersion(reconcileUpdates.capture(), any());
        AiToolActionDO reconciled = reconcileUpdates.getAllValues().get(2);
        assertThat(reconciled.getStatus()).isEqualTo(AiToolActionDO.STATUS_EXECUTED);
        assertThat(reconciled.getVerifiedBy()).isEqualTo(AiToolActionDO.VERIFIED_BY_PROGRAM);
        assertThat(reconciled.getVerifyResult()).isEqualTo(AiToolActionDO.VERIFY_APPLIED);
        assertThat(reconciled.getVerifyEvidence()).isEqualTo("getPayment:items=1");
        assertThat(reconciled.getResultCode()).isEqualTo("reconciled-applied");
    }

    @Test
    void reconcileOnlyAllowsUndeterminedResultsAndNeedsUsableEvidence() {
        mockWriteTool();
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(1);

        // 已确认（还没执行）的动作不能被核对改写
        when(actionMapper.selectById(ACTION_ID)).thenReturn(writeAction(AiToolActionDO.STATUS_CONFIRMED));
        assertThatThrownBy(() -> service.reconcile(
                        ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "MANUAL", "APPLIED", null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_NOT_RECONCILABLE));

        // 核对查询不可用：动作保持未定，不猜结论
        when(actionMapper.selectById(ACTION_ID)).thenReturn(writeAction(AiToolActionDO.STATUS_EXECUTING));
        when(toolExecutor.executeReconcile(any(), any(), any()))
                .thenReturn(
                        new AiConnectorExecutionResultDTO().setStatus("FAILED").setDetailCode("TIMEOUT"));
        assertThatThrownBy(() -> service.reconcile(
                        ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "PROGRAM", null, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ACTION_RECONCILE_FAILED));
        verify(actionMapper, never()).updateWithVersion(any(), any());

        // 核对查询查到 0 条：业务未生效 → FAILED（可以重新发起）
        when(toolExecutor.executeReconcile(any(), any(), any()))
                .thenReturn(new AiConnectorExecutionResultDTO()
                        .setStatus("COMPLETE")
                        .setItemCount(0));
        service.reconcile(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "PROGRAM", null, null);
        ArgumentCaptor<AiToolActionDO> updates = ArgumentCaptor.forClass(AiToolActionDO.class);
        verify(actionMapper).updateWithVersion(updates.capture(), any());
        assertThat(updates.getValue().getStatus()).isEqualTo(AiToolActionDO.STATUS_FAILED);
        assertThat(updates.getValue().getVerifyResult()).isEqualTo(AiToolActionDO.VERIFY_NOT_APPLIED);
        assertThat(updates.getValue().getResultCode()).isEqualTo("reconciled-not-applied");

        // 人工核对：结论必填、说明受长度与控制字符约束
        assertThatThrownBy(() -> service.reconcile(
                        ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "MANUAL", "MAYBE", null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.reconcile(
                        ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "UNKNOWN_MODE", "APPLIED", null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.reconcile(
                        ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "MANUAL", "APPLIED", "x".repeat(201)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));

        service.reconcile(ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "manual", "applied", "已与业务方电话确认入账");
        ArgumentCaptor<AiToolActionDO> manualUpdates = ArgumentCaptor.forClass(AiToolActionDO.class);
        verify(actionMapper, times(2)).updateWithVersion(manualUpdates.capture(), any());
        assertThat(manualUpdates.getAllValues().get(1).getVerifiedBy()).isEqualTo(AiToolActionDO.VERIFIED_BY_MANUAL);
        assertThat(manualUpdates.getAllValues().get(1).getVerifyEvidence()).isEqualTo("已与业务方电话确认入账");

        // 并发核对：CAS 失败即冲突
        when(actionMapper.updateWithVersion(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.reconcile(
                        ACTION_ID, APPLICATION_ID, SUBJECT_TYPE, EXTERNAL_USER, "MANUAL", "APPLIED", null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }
}
