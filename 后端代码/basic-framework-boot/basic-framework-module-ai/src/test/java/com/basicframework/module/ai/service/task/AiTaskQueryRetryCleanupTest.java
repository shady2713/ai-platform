package com.basicframework.module.ai.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.mysql.conversation.AiConversationMessageMapper;
import com.basicframework.module.ai.dal.mysql.event.AiRunEventMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper;
import com.basicframework.module.ai.dal.mysql.task.AiRetentionCleanupMapper;
import com.basicframework.module.ai.dal.mysql.task.AiTaskClaimMapper;
import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.job.AiRetentionCleanupJob;
import com.basicframework.module.ai.service.authorization.AiExecutionContextFactory;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.task.dto.AiRetentionCleanupResultDTO;
import com.basicframework.module.ai.service.task.dto.AiRunProgressDTO;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** O06 任务查询、人工重试与保留期清理：进度与结果引用、UNKNOWN 不可重试、清理带引用守卫。 */
class AiTaskQueryRetryCleanupTest {

    private static final Long RUN_ID = 41L;

    private static final Long APP_ID = 5L;

    private static final String EXTERNAL_USER = "u-1001";

    private static final Long CONVERSATION_ID = 31L;

    private final AiTaskClaimMapper claimMapper = mock(AiTaskClaimMapper.class);

    private final AiRetentionCleanupMapper cleanupMapper = mock(AiRetentionCleanupMapper.class);

    private final AiRunMapper runMapper = mock(AiRunMapper.class);

    private final AiRunTaskMapper taskMapper = mock(AiRunTaskMapper.class);

    private final AiRunEventMapper eventMapper = mock(AiRunEventMapper.class);

    private final AiConversationMessageMapper messageMapper = mock(AiConversationMessageMapper.class);

    private final AiExecutionContextFactory executionContextFactory = mock(AiExecutionContextFactory.class);

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiTaskServiceImpl service = new AiTaskServiceImpl(
            claimMapper,
            cleanupMapper,
            runMapper,
            taskMapper,
            eventMapper,
            messageMapper,
            executionContextFactory,
            subjectResolver);

    @BeforeEach
    void setUp() {
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiConversationSubject(APP_ID, AiSubjectType.USER, EXTERNAL_USER)));
        when(executionContextFactory.rebuild(eq(APP_ID), eq(AiSubjectType.USER), eq(EXTERNAL_USER), any()))
                .thenReturn(new AiExecutionContext(
                        APP_ID, "USER", EXTERNAL_USER, Set.of(10L), Set.of("report-1"), "it", 1L));
    }

    private static AiRunDO run(String status) {
        return new AiRunDO()
                .setId(RUN_ID)
                .setRunKey("run_0123456789abcdef01234567")
                .setApplicationId(APP_ID)
                .setSubjectType("USER")
                .setExternalUserId(EXTERNAL_USER)
                .setConversationId(CONVERSATION_ID)
                .setStatus(status)
                .setStepCount(1)
                .setEventSeq(2)
                .setVersion(3);
    }

    private static AiRunTaskDO task(String status, int attempts) {
        return new AiRunTaskDO()
                .setId(61L)
                .setRunId(RUN_ID)
                .setTaskKind(AiRunTaskDO.KIND_RUN_STEP)
                .setStatus(status)
                .setAttemptCount(attempts)
                .setVersion(1);
    }

    private void stubRunAndTask(AiRunDO run, AiRunTaskDO task) {
        when(runMapper.selectOwned(RUN_ID, APP_ID, "USER", EXTERNAL_USER)).thenReturn(run);
        when(runMapper.selectById(RUN_ID)).thenReturn(run);
        when(taskMapper.selectByRunAndKind(RUN_ID, AiRunTaskDO.KIND_RUN_STEP)).thenReturn(task);
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void progressReportsStatusResultReferenceAndRetryability() {
        stubRunAndTask(run(AiRunDO.STATUS_SUCCEEDED), task(AiRunTaskDO.STATUS_SUCCEEDED, 1));
        when(messageMapper.selectAll(CONVERSATION_ID))
                .thenReturn(List.of(new AiConversationMessageDO()
                        .setId(77L)
                        .setConversationId(CONVERSATION_ID)
                        .setRole(AiConversationMessageDO.ROLE_ASSISTANT)
                        .setContent("订单 A-1 已发货")
                        .setContentHash("b".repeat(64))
                        .setSourceRunId(RUN_ID)
                        .setStatus(AiConversationMessageDO.STATUS_ACTIVE)));

        AiRunProgressDTO progress = service.progress(RUN_ID);

        assertThat(progress.getStatus()).isEqualTo(AiRunDO.STATUS_SUCCEEDED);
        assertThat(progress.getLatestSeq()).isEqualTo(2);
        assertThat(progress.getResultMessageId()).as("结果引用只给标识").isEqualTo(77L);
        assertThat(progress.getResultDigest()).as("结果引用只给摘要，不复制正文").hasSize(64);
        assertThat(progress.getTaskStatus()).isEqualTo(AiRunTaskDO.STATUS_SUCCEEDED);
        assertThat(progress.isRetryable()).as("已成功的任务不可重试").isFalse();
        assertThat(progress.getRetryBlockedReason()).contains("已成功");
        assertThat(progress.toString()).as("进度不携带结果正文").doesNotContain("已发货");
    }

    @Test
    void failedTaskIsRetryableOnlyAfterIdentityRebuildSucceeds() {
        stubRunAndTask(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_FAILED, 3));
        when(runMapper.updateWithVersion(any(), anyInt())).thenReturn(1);
        when(taskMapper.updateWithVersion(any(), anyInt())).thenReturn(1);

        assertThat(service.progress(RUN_ID).isRetryable()).isTrue();
        assertThat(service.progress(RUN_ID).getRetryBlockedReason()).isNull();

        service.retry(RUN_ID, 3);

        ArgumentCaptor<AiRunTaskDO> taskCaptor = ArgumentCaptor.forClass(AiRunTaskDO.class);
        verify(taskMapper).updateWithVersion(taskCaptor.capture(), eq(1));
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(AiRunTaskDO.STATUS_QUEUED);
        assertThat(taskCaptor.getValue().getAttemptCount()).as("人工重试重置自动重试预算").isZero();
        assertThat(taskCaptor.getValue().getLeaseOwner()).isNull();
        ArgumentCaptor<AiRunDO> runCaptor = ArgumentCaptor.forClass(AiRunDO.class);
        verify(runMapper).updateWithVersion(runCaptor.capture(), eq(3));
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(AiRunDO.STATUS_ACCEPTED);
        verify(executionContextFactory).rebuild(eq(APP_ID), eq(AiSubjectType.USER), eq(EXTERNAL_USER), any());
    }

    @Test
    void unknownTaskCannotBeRetriedNormally() {
        stubRunAndTask(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_UNKNOWN, 1));

        AiRunProgressDTO progress = service.progress(RUN_ID);
        assertThat(progress.isRetryable()).isFalse();
        assertThat(progress.getRetryBlockedReason()).contains("结果未知");

        assertThatThrownBy(() -> service.retry(RUN_ID, 3))
                .as("UNKNOWN 任务重复执行可能产生第二份副作用，拒绝普通重试")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_TASK_NOT_RETRYABLE));
        verify(taskMapper, never()).updateWithVersion(any(), anyInt());
    }

    @Test
    void retryRejectsRunningTaskAndRevokedIdentity() {
        stubRunAndTask(run(AiRunDO.STATUS_RUNNING), task(AiRunTaskDO.STATUS_RUNNING, 1));
        assertThatThrownBy(() -> service.retry(RUN_ID, 3))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_TASK_NOT_RETRYABLE));

        stubRunAndTask(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_FAILED, 1));
        when(executionContextFactory.rebuild(eq(APP_ID), eq(AiSubjectType.USER), eq(EXTERNAL_USER), any()))
                .thenThrow(com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception(
                        AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
        assertThatThrownBy(() -> service.retry(RUN_ID, 3))
                .as("失权后人工重试同样被拒绝")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
        verify(taskMapper, never()).updateWithVersion(any(), anyInt());

        when(runMapper.selectOwned(RUN_ID, APP_ID, "USER", EXTERNAL_USER)).thenReturn(null);
        assertThatThrownBy(() -> service.retry(RUN_ID, 3))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThatThrownBy(() -> service.progress(RUN_ID))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThatThrownBy(() -> service.progress(null))
                .as("缺少运行编号同样按不存在处理")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
    }

    @Test
    void retryLosesTheRaceToAnotherWrite() {
        stubRunAndTask(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_FAILED, 1));
        when(taskMapper.updateWithVersion(any(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.retry(RUN_ID, 3))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void pageProgressIsScopedToTheCurrentSubject() {
        when(runMapper.selectPageBySubject(any(), eq(APP_ID), eq("USER"), eq(EXTERNAL_USER)))
                .thenReturn(new PageResult<>(List.of(run(AiRunDO.STATUS_ACCEPTED)), 1L));
        stubRunAndTask(run(AiRunDO.STATUS_ACCEPTED), task(AiRunTaskDO.STATUS_QUEUED, 0));

        PageResult<AiRunProgressDTO> page = service.pageProgress(new PageParam());

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList()).singleElement().satisfies(item -> {
            assertThat(item.getRunId()).isEqualTo(RUN_ID);
            assertThat(item.isRetryable()).as("排队中的任务不需要人工重试").isFalse();
        });
    }

    @Test
    void retryRejectsMissingTaskAndRunLevelTerminalStates() {
        // 缺少首任务：不可重试
        stubRunAndTask(run(AiRunDO.STATUS_FAILED), null);
        assertThat(service.progress(RUN_ID).getRetryBlockedReason()).contains("缺少可重试的任务");
        assertThatThrownBy(() -> service.retry(RUN_ID, 3))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_TASK_NOT_RETRYABLE));

        // 任务失败但运行已取消：运行级终态同样不可重试
        stubRunAndTask(run(AiRunDO.STATUS_CANCELLED), task(AiRunTaskDO.STATUS_FAILED, 1));
        assertThat(service.progress(RUN_ID).getRetryBlockedReason()).contains("运行已结束");

        // 运行行 CAS 失败（并发写入）：返回状态冲突，不留下半成品
        stubRunAndTask(run(AiRunDO.STATUS_FAILED), task(AiRunTaskDO.STATUS_FAILED, 1));
        when(taskMapper.updateWithVersion(any(), anyInt())).thenReturn(1);
        when(runMapper.updateWithVersion(any(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.retry(RUN_ID, 3))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));
    }

    @Test
    void cleanupDeletesInReferenceOrderWithBatchesAndStopsWhenNothingLeft() {
        when(cleanupMapper.deleteEventsOfTerminalRuns(any(), eq(100))).thenReturn(5, 0);
        when(cleanupMapper.deleteTasksOfTerminalRuns(any(), eq(100))).thenReturn(4, 0);
        when(cleanupMapper.deleteIdempotencyOfTerminalRuns(any(), eq(100))).thenReturn(6, 0);
        when(cleanupMapper.deleteTerminalRuns(any(), eq(100))).thenReturn(3, 0);
        when(cleanupMapper.deleteMessagesOfClosedConversations(any(), eq(100))).thenReturn(2, 0);
        when(cleanupMapper.deleteClosedConversations(any(), eq(100))).thenReturn(1, 0);

        AiRetentionCleanupResultDTO result = service.cleanup(Duration.ofDays(30), 100, 10);

        assertThat(result.getEvents()).isEqualTo(5);
        assertThat(result.getTasks()).isEqualTo(4);
        assertThat(result.getIdempotency()).as("幂等记录先于运行行清理（外键 RESTRICT）").isEqualTo(6);
        assertThat(result.getRuns()).isEqualTo(3);
        assertThat(result.getMessages()).isEqualTo(2);
        assertThat(result.getConversations()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(21);
        // 第二批已经无事可做：立即停止，不空转完 maxBatches
        verify(cleanupMapper, org.mockito.Mockito.times(2)).deleteEventsOfTerminalRuns(any(), anyInt());

        AiRetentionCleanupResultDTO bounded = service.cleanup(Duration.ofDays(-1), 0, 0);
        assertThat(bounded).isNotNull();
        verify(cleanupMapper, org.mockito.Mockito.atLeastOnce()).deleteClosedConversations(any(), eq(1));
    }

    @Test
    void cleanupJobReportsPerCategoryCounts() {
        when(cleanupMapper.deleteEventsOfTerminalRuns(any(), eq(200))).thenReturn(1, 0);
        when(cleanupMapper.deleteTasksOfTerminalRuns(any(), eq(200))).thenReturn(0, 0);
        when(cleanupMapper.deleteIdempotencyOfTerminalRuns(any(), eq(200))).thenReturn(0, 0);
        when(cleanupMapper.deleteTerminalRuns(any(), eq(200))).thenReturn(0, 0);
        when(cleanupMapper.deleteMessagesOfClosedConversations(any(), eq(200))).thenReturn(0, 0);
        when(cleanupMapper.deleteClosedConversations(any(), eq(200))).thenReturn(0, 0);
        AiRetentionCleanupJob job = new AiRetentionCleanupJob(service, Duration.ofDays(30), 200, 10);

        assertThat(job.execute("")).contains("事件 1").contains("会话 0");
    }
}
