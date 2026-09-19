package com.basicframework.module.ai.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.task.AiTaskClaimMapper;
import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.job.AiTaskRecoveryJob;
import com.basicframework.module.ai.service.authorization.AiExecutionContextFactory;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** O03 任务领取与租约：CAS 领取、栅栏续租/落库、恢复与重试上限、身份重建。 */
class AiTaskServiceImplTest {

    private static final Long TASK_ID = 61L;

    private static final Long RUN_ID = 41L;

    private final AiTaskClaimMapper claimMapper = mock(AiTaskClaimMapper.class);

    private final AiRunMapper runMapper = mock(AiRunMapper.class);

    private final AiExecutionContextFactory executionContextFactory = mock(AiExecutionContextFactory.class);

    private final AiTaskServiceImpl service = new AiTaskServiceImpl(claimMapper, runMapper, executionContextFactory);

    private static AiRunTaskDO task(int epoch, int attempt) {
        return new AiRunTaskDO()
                .setId(TASK_ID)
                .setRunId(RUN_ID)
                .setTaskKind(AiRunTaskDO.KIND_RUN_STEP)
                .setStatus(AiRunTaskDO.STATUS_QUEUED)
                .setAttemptCount(attempt)
                .setClaimedEpoch(epoch)
                .setMaxAttempts(3)
                .setVersion(0);
    }

    private static AiTaskLeaseDTO lease(int epoch) {
        return new AiTaskLeaseDTO()
                .setTaskId(TASK_ID)
                .setRunId(RUN_ID)
                .setTaskKind(AiRunTaskDO.KIND_RUN_STEP)
                .setOwner("worker-a")
                .setEpoch(epoch)
                .setAttempt(1);
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void claimIsCasGuardedSoOnlyOneWorkerWins() {
        when(claimMapper.selectClaimable(10)).thenReturn(List.of(task(0, 0)));
        when(claimMapper.claim(TASK_ID, 0, "worker-a", 60)).thenReturn(1);
        when(claimMapper.claim(TASK_ID, 0, "worker-b", 60)).thenReturn(0);

        List<AiTaskLeaseDTO> claimed = service.claim("worker-a", 10, 60);

        assertThat(claimed).singleElement().satisfies(item -> {
            assertThat(item.getOwner()).isEqualTo("worker-a");
            assertThat(item.getEpoch()).as("领取成功后代次递增，成为续租与落库的栅栏").isEqualTo(1);
            assertThat(item.getAttempt()).isEqualTo(1);
        });
        assertThat(service.claim("worker-b", 10, 60)).as("同一任务不会被第二个 worker 领到").isEmpty();
        verify(claimMapper).claim(TASK_ID, 0, "worker-a", 60);
    }

    @Test
    void claimBoundsBatchAndLeaseAndRejectsInvalidInput() {
        when(claimMapper.selectClaimable(50)).thenReturn(List.of());

        service.claim("worker-a", 1_000, 100_000);

        verify(claimMapper).selectClaimable(50);
        assertThatThrownBy(() -> service.claim(" ", 10, 60))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.claim("worker-a", 0, 60))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.claim("worker-a", 10, 0))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }

    @Test
    void heartbeatAndFinishCarryTheLeaseFence() {
        when(claimMapper.heartbeat(TASK_ID, "worker-a", 1, 60)).thenReturn(1);
        assertThat(service.heartbeat(lease(1), 60)).isTrue();

        // 租约被新 worker 接管（代次已递增）：旧 worker 续租与落库都失败
        when(claimMapper.heartbeat(TASK_ID, "worker-a", 1, 60)).thenReturn(0);
        when(claimMapper.finish(TASK_ID, "worker-a", 1, AiRunTaskDO.STATUS_SUCCEEDED, null))
                .thenReturn(0);
        assertThat(service.heartbeat(lease(1), 60)).isFalse();
        assertThat(service.finish(lease(1), AiRunTaskDO.STATUS_SUCCEEDED, null))
                .as("迟到的旧 worker 不能覆盖新 worker 的结果")
                .isFalse();

        when(claimMapper.finish(TASK_ID, "worker-a", 1, AiRunTaskDO.STATUS_FAILED, "TIMEOUT"))
                .thenReturn(1);
        assertThat(service.finish(lease(1), AiRunTaskDO.STATUS_FAILED, "TIMEOUT"))
                .isTrue();
    }

    @Test
    void heartbeatAndFinishValidateTheLeaseAndStatus() {
        assertThatThrownBy(() -> service.heartbeat(null, 60))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.heartbeat(lease(0), 60))
                .as("缺少领取代次的租约不可用")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.finish(lease(1), " ", null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        verify(claimMapper, never()).finish(any(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void recoveryRequeuesExpiredLeasesWithinRetryLimit() {
        when(claimMapper.recoverExpired(5, 200)).thenReturn(2);
        when(claimMapper.recoverExpired(0, 1)).thenReturn(1);

        assertThat(service.recoverExpiredLeases(5, 200)).isEqualTo(2);
        assertThat(service.recoverExpiredLeases(-1, 0))
                .as("非法参数收敛为安全边界（重试等待 0 秒、单批 1 条）")
                .isEqualTo(1);
        verify(claimMapper).recoverExpired(0, 1);
    }

    @Test
    void identityIsRebuiltFromServerSideFacts() {
        when(runMapper.selectById(RUN_ID))
                .thenReturn(new AiRunDO()
                        .setId(RUN_ID)
                        .setApplicationId(5L)
                        .setSubjectType("USER")
                        .setExternalUserId("u-1001"));
        when(executionContextFactory.rebuild(5L, AiSubjectType.USER, "u-1001", List.of()))
                .thenReturn(new AiExecutionContext(5L, "USER", "u-1001", Set.of(10L), Set.of("report-1"), "it", 1L));

        AiExecutionContext context = service.rebuildIdentity(RUN_ID);

        assertThat(context.externalUserId()).isEqualTo("u-1001");
        assertThat(context.isDeny()).isFalse();

        // 撤销后重建直接失败：worker 不得继续执行受限步骤
        when(executionContextFactory.rebuild(eq(5L), eq(AiSubjectType.USER), eq("u-1001"), any()))
                .thenThrow(com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception(
                        AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));
        assertThatThrownBy(() -> service.rebuildIdentity(RUN_ID))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_AUTHORIZATION_DENIED));

        when(runMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.rebuildIdentity(999L))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThatThrownBy(() -> service.rebuildIdentity(null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
    }

    @Test
    void identityRebuildRejectsUnknownSubjectType() {
        when(runMapper.selectById(RUN_ID))
                .thenReturn(new AiRunDO()
                        .setId(RUN_ID)
                        .setApplicationId(5L)
                        .setSubjectType("ROOT")
                        .setExternalUserId("u-1001"));

        assertThatThrownBy(() -> service.rebuildIdentity(RUN_ID))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
    }

    @Test
    void recoveryJobReportsRecoveredCount() {
        when(claimMapper.recoverExpired(5, 200)).thenReturn(3);
        AiTaskRecoveryJob job = new AiTaskRecoveryJob(service, 5, 200);

        assertThat(job.execute("")).contains("3");
    }
}
