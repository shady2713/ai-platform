package com.basicframework.module.ai.service.tool.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private final AiToolActionServiceImpl service =
            new AiToolActionServiceImpl(actionMapper, policyGate, toolExecutor, toolService);

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

        AiToolActionDO created = service.createFromDecision(
                new AiToolDecision(AiToolDecision.Outcome.CONFIRM, version("CONFIRM"), 71L, "getOrders", ARGUMENTS),
                RUN_ID,
                APPLICATION_ID,
                SUBJECT_TYPE,
                EXTERNAL_USER);

        assertThat(created.getArgumentsHash()).isEqualTo(AiToolActionStateMachine.argumentsHash(ARGUMENTS));
        assertThat(created.getChallenge()).hasSize(AiToolActionStateMachine.CHALLENGE_LENGTH);
        assertThat(created.getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(created.getStatus()).isEqualTo(AiToolActionDO.STATUS_PENDING);
        assertThat(created.getExternalUserId()).isEqualTo(EXTERNAL_USER);

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
}
