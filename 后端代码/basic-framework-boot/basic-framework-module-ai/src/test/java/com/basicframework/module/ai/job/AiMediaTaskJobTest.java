package com.basicframework.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.media.AiMediaStepExecutor;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 媒体任务常驻消费者（X03）：先恢复过期租约再领取；没有执行器承接操作时以稳定原因码失败；
 * 执行异常只落平台错误码（{@code 1_...}）或 {@code execution-failed}，永不泄漏异常正文；
 * {@code finish} 未命中租约栅栏（返回 false）时结果被丢弃，计数器不虚增。
 */
class AiMediaTaskJobTest {

    private static final String WORKER_PREFIX = "media-task-test";

    private final AiMediaTaskService taskService = mock(AiMediaTaskService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AiMediaStepExecutor> executors = mock(ObjectProvider.class);

    private AiMediaTaskLeaseDTO lease() {
        return new AiMediaTaskLeaseDTO()
                .setTaskId(77L)
                .setOwner(WORKER_PREFIX + "-abc")
                .setEpoch(1);
    }

    private AiMediaTaskJob job(int batchSize, int leaseSeconds) {
        return new AiMediaTaskJob(taskService, executors, WORKER_PREFIX, batchSize, leaseSeconds, 30, 50);
    }

    private void givenClaimedTask(String operation) {
        AiMediaTaskLeaseDTO lease = lease();
        when(taskService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(lease));
        when(taskService.getTaskForExecution(77L))
                .thenReturn(new AiMediaTaskDO().setId(77L).setOperation(operation));
    }

    @Test
    void recoveryRunsBeforeClaiming() {
        when(taskService.recoverExpiredLeases(30, 50)).thenReturn(3);
        when(taskService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        String result = job(5, 300).execute(null);

        assertThat(result).isEqualTo("recovered=3,claimed=0,succeeded=0,failed=0");
        InOrder order = inOrder(taskService);
        order.verify(taskService).recoverExpiredLeases(30, 50);
        order.verify(taskService).claim(anyString(), anyInt(), anyInt());
    }

    @Test
    void missingExecutorFailsTaskWithStableReasonCode() {
        givenClaimedTask(AiMediaTaskDO.OPERATION_GENERATE);
        when(executors.stream()).thenReturn(Stream.empty());
        when(taskService.finish(any(), any())).thenReturn(true);

        String result = job(5, 300).execute(null);

        assertThat(result).isEqualTo("recovered=0,claimed=1,succeeded=0,failed=1");
        ArgumentCaptor<AiMediaStepOutcome> outcome = ArgumentCaptor.forClass(AiMediaStepOutcome.class);
        verify(taskService).finish(eq(lease()), outcome.capture());
        assertThat(outcome.getValue().getStatus()).isEqualTo(AiMediaTaskDO.STATUS_FAILED);
        assertThat(outcome.getValue().getFailureCode())
                .as("没有执行器时任务不能永远留在 RUNNING")
                .isEqualTo("executor-unavailable");
    }

    @Test
    void executorSelectedByOperationAndSuccessIsCounted() {
        givenClaimedTask(AiMediaTaskDO.OPERATION_GENERATE);
        AiMediaStepExecutor imageExecutor = executorFor(AiMediaTaskDO.OPERATION_GENERATE);
        AiMediaStepExecutor audioExecutor = executorFor(AiMediaTaskDO.OPERATION_TRANSCRIBE);
        when(executors.stream()).thenReturn(Stream.of(audioExecutor, imageExecutor));
        when(imageExecutor.execute(any(), any())).thenReturn(AiMediaStepOutcome.succeeded(1, "TOKEN", 30L, "REPORTED"));
        when(taskService.finish(any(), any())).thenReturn(true);

        assertThat(job(5, 300).execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=1,failed=0");
        verify(audioExecutor, never()).execute(any(), any());
        verify(imageExecutor).execute(eq(lease()), any(AiMediaTaskDO.class));
    }

    @Test
    void speechOperationsAreDispatchedToTheSpeechExecutor() {
        givenClaimedTask(AiMediaTaskDO.OPERATION_TRANSCRIBE);
        AiMediaStepExecutor imageExecutor = executorFor(AiMediaTaskDO.OPERATION_GENERATE);
        AiMediaStepExecutor speechExecutor =
                executorFor(AiMediaTaskDO.OPERATION_TRANSCRIBE, AiMediaTaskDO.OPERATION_SYNTHESIZE);
        when(executors.stream()).thenReturn(Stream.of(imageExecutor, speechExecutor));
        when(speechExecutor.execute(any(), any()))
                .thenReturn(AiMediaStepOutcome.succeeded(1, "TOKEN", 12L, "REPORTED"));
        when(taskService.finish(any(), any())).thenReturn(true);

        assertThat(job(5, 300).execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=1,failed=0");
        verify(imageExecutor, never()).execute(any(), any());
        verify(speechExecutor).execute(eq(lease()), any(AiMediaTaskDO.class));
    }

    @Test
    void executorFailureIsReportedAsFailedOutcome() {
        givenClaimedTask(AiMediaTaskDO.OPERATION_GENERATE);
        AiMediaStepExecutor imageExecutor = executorFor(AiMediaTaskDO.OPERATION_GENERATE);
        when(executors.stream()).thenReturn(Stream.of(imageExecutor));
        when(imageExecutor.execute(any(), any()))
                .thenReturn(AiMediaStepOutcome.failed(
                        String.valueOf(AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID.getCode())));
        when(taskService.finish(any(), any())).thenReturn(true);

        assertThat(job(5, 300).execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=0,failed=1");
        ArgumentCaptor<AiMediaStepOutcome> outcome = ArgumentCaptor.forClass(AiMediaStepOutcome.class);
        verify(taskService).finish(any(), outcome.capture());
        assertThat(outcome.getValue().getFailureCode())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID.getCode()));
    }

    @Test
    void serviceExceptionFromExecutorKeepsPlatformErrorCodeWithoutLeakingMessage() {
        givenClaimedTask(AiMediaTaskDO.OPERATION_GENERATE);
        AiMediaStepExecutor imageExecutor = executorFor(AiMediaTaskDO.OPERATION_GENERATE);
        when(executors.stream()).thenReturn(Stream.of(imageExecutor));
        when(imageExecutor.execute(any(), any()))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID));
        when(taskService.finish(any(), any())).thenReturn(true);

        job(5, 300).execute(null);

        assertThat(failureCodeWritten())
                .as("平台错误码按其十进制形式落库（与 AiEvalRunServiceImpl 同口径）")
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID.getCode()));
    }

    @Test
    void unexpectedExceptionFromExecutorCollapsesToStableReasonWithoutUpstreamText() {
        givenClaimedTask(AiMediaTaskDO.OPERATION_GENERATE);
        AiMediaStepExecutor imageExecutor = executorFor(AiMediaTaskDO.OPERATION_GENERATE);
        when(executors.stream()).thenReturn(Stream.of(imageExecutor));
        when(imageExecutor.execute(any(), any()))
                .thenThrow(new IllegalStateException("vendor raw body: https://vendor.example.com/tmp/secret.png"));
        when(taskService.finish(any(), any())).thenReturn(true);

        job(5, 300).execute(null);

        assertThat(failureCodeWritten()).as("异常正文与上游地址不得进入任务行").isEqualTo("execution-failed");
    }

    @Test
    void serviceExceptionOutsidePlatformCodeRangeCollapsesToExecutionFailed() {
        givenClaimedTask(AiMediaTaskDO.OPERATION_GENERATE);
        AiMediaStepExecutor imageExecutor = executorFor(AiMediaTaskDO.OPERATION_GENERATE);
        when(executors.stream()).thenReturn(Stream.of(imageExecutor));
        when(imageExecutor.execute(any(), any())).thenThrow(new ServiceException(new ErrorCode(400, "参数不合法")));
        when(taskService.finish(any(), any())).thenReturn(true);

        job(5, 300).execute(null);

        assertThat(failureCodeWritten()).isEqualTo("execution-failed");
    }

    @Test
    void nullOutcomeFromExecutorIsTreatedAsExecutionFailure() {
        givenClaimedTask(AiMediaTaskDO.OPERATION_GENERATE);
        AiMediaStepExecutor imageExecutor = executorFor(AiMediaTaskDO.OPERATION_GENERATE);
        when(executors.stream()).thenReturn(Stream.of(imageExecutor));
        when(imageExecutor.execute(any(), any())).thenReturn(null);
        when(taskService.finish(any(), any())).thenReturn(true);

        job(5, 300).execute(null);

        assertThat(failureCodeWritten()).isEqualTo("execution-failed");
    }

    @Test
    void taskDeletedAfterClaimIsFinalizedInsteadOfLeavingOrphanRunningRow() {
        AiMediaTaskLeaseDTO lease = lease();
        when(taskService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(lease));
        when(taskService.getTaskForExecution(77L)).thenReturn(null);
        when(taskService.finish(any(), any())).thenReturn(true);

        job(5, 300).execute(null);

        assertThat(failureCodeWritten()).isEqualTo("execution-failed");
        verify(executors, never()).stream();
    }

    @Test
    void fenceMissDropsLateResultAndDoesNotCountIt() {
        givenClaimedTask(AiMediaTaskDO.OPERATION_GENERATE);
        AiMediaStepExecutor imageExecutor = executorFor(AiMediaTaskDO.OPERATION_GENERATE);
        when(executors.stream()).thenReturn(Stream.of(imageExecutor));
        when(imageExecutor.execute(any(), any())).thenReturn(AiMediaStepOutcome.succeeded(1, "TOKEN", 30L, "REPORTED"));
        when(taskService.finish(any(), any())).thenReturn(false);

        assertThat(job(5, 300).execute(null))
                .as("栅栏未命中时结果被丢弃，不虚增成功数")
                .isEqualTo("recovered=0,claimed=1,succeeded=0,failed=0");
    }

    @Test
    void claimBoundsAreAtLeastOneAndWorkerIdCarriesThePrefix() {
        when(executors.stream()).thenReturn(Stream.empty());
        when(taskService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        job(0, 0).execute(null);

        verify(taskService).claim(argThat(workerId -> workerId.startsWith(WORKER_PREFIX + "-")), eq(1), eq(1));
    }

    @Test
    void configuredBatchAndLeaseArePassedToClaim() {
        when(executors.stream()).thenReturn(Stream.empty());
        when(taskService.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        job(7, 120).execute(null);

        verify(taskService).claim(anyString(), eq(7), eq(120));
    }

    private String failureCodeWritten() {
        ArgumentCaptor<AiMediaStepOutcome> outcome = ArgumentCaptor.forClass(AiMediaStepOutcome.class);
        verify(taskService).finish(any(), outcome.capture());
        assertThat(outcome.getValue().getStatus()).isEqualTo(AiMediaTaskDO.STATUS_FAILED);
        assertThat(outcome.getValue().getResultCount()).isZero();
        return outcome.getValue().getFailureCode();
    }

    /** 只承接指定操作的执行器替身：其它操作不应被调用。 */
    private static AiMediaStepExecutor executorFor(String... operations) {
        AiMediaStepExecutor executor = mock(AiMediaStepExecutor.class);
        when(executor.supports(any())).thenAnswer(invocation -> java.util.Arrays.asList(operations)
                .contains(invocation.getArgument(0, String.class)));
        return executor;
    }
}
