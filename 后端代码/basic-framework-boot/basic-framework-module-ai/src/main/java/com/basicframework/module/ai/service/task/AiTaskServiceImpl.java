package com.basicframework.module.ai.service.task;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_NOT_FOUND;

import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.task.AiTaskClaimMapper;
import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.authorization.AiExecutionContextFactory;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 可恢复任务领取与租约实现（O03）。
 *
 * <p>所有写操作都是单条条件更新（CAS）并各自构成一个短事务：领取、续租与落库都不持有长事务，
 * 因此外部模型/连接器调用可以在事务之外进行；worker 的身份在每次执行前由
 * {@link AiExecutionContextFactory} 从服务端事实重建，不继承线程上的旧身份。
 */
@Service
@RequiredArgsConstructor
public class AiTaskServiceImpl implements AiTaskService {

    /** 单次领取的批次上限（避免一个 worker 一次抓走整张表）。 */
    private static final int MAX_CLAIM_BATCH = 50;

    /** 恢复扫描的单批上限。 */
    private static final int MAX_RECOVER_BATCH = 500;

    /** 租约时长上限（防止租约过长导致故障后恢复迟缓）。 */
    private static final int MAX_LEASE_SECONDS = 3_600;

    private final AiTaskClaimMapper claimMapper;

    private final AiRunMapper runMapper;

    private final AiExecutionContextFactory executionContextFactory;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AiTaskLeaseDTO> claim(String workerId, int limit, int leaseSeconds) {
        if (!StringUtils.hasText(workerId) || limit < 1 || leaseSeconds < 1) {
            throw exception(AI_REQUEST_INVALID);
        }
        int batch = Math.min(limit, MAX_CLAIM_BATCH);
        int lease = Math.min(leaseSeconds, MAX_LEASE_SECONDS);
        List<AiTaskLeaseDTO> claimed = new ArrayList<>();
        for (AiRunTaskDO candidate : claimMapper.selectClaimable(batch)) {
            int expectedEpoch = candidate.getClaimedEpoch() == null ? 0 : candidate.getClaimedEpoch();
            if (claimMapper.claim(candidate.getId(), expectedEpoch, workerId, lease) == 1) {
                claimed.add(new AiTaskLeaseDTO()
                        .setTaskId(candidate.getId())
                        .setRunId(candidate.getRunId())
                        .setTaskKind(candidate.getTaskKind())
                        .setOwner(workerId)
                        .setEpoch(expectedEpoch + 1)
                        .setAttempt((candidate.getAttemptCount() == null ? 0 : candidate.getAttemptCount()) + 1));
            }
        }
        return claimed;
    }

    @Override
    public boolean heartbeat(AiTaskLeaseDTO lease, int leaseSeconds) {
        requireLease(lease);
        int leaseBound = Math.min(Math.max(leaseSeconds, 1), MAX_LEASE_SECONDS);
        // 栅栏：只有仍持有该代次租约的 worker 能续租；失败即表示租约已被接管
        return claimMapper.heartbeat(lease.getTaskId(), lease.getOwner(), lease.getEpoch(), leaseBound) == 1;
    }

    @Override
    public boolean finish(AiTaskLeaseDTO lease, String status, String errorCode) {
        requireLease(lease);
        if (!StringUtils.hasText(status)) {
            throw exception(AI_REQUEST_INVALID);
        }
        // 栅栏：旧 worker 迟到的落库不会覆盖新 worker 的结果
        return claimMapper.finish(lease.getTaskId(), lease.getOwner(), lease.getEpoch(), status, errorCode) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpiredLeases(int retryDelaySeconds, int limit) {
        int delay = Math.max(retryDelaySeconds, 0);
        int batch = Math.min(Math.max(limit, 1), MAX_RECOVER_BATCH);
        return claimMapper.recoverExpired(delay, batch);
    }

    @Override
    public int countActiveLeases() {
        return claimMapper.countActiveLeases();
    }

    @Override
    public AiExecutionContext rebuildIdentity(Long runId) {
        AiRunDO run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) {
            throw exception(AI_RUN_NOT_FOUND);
        }
        AiSubjectType subjectType =
                AiSubjectType.parse(run.getSubjectType()).orElseThrow(() -> exception(AI_RUN_NOT_FOUND));
        // 从服务端事实重建：应用停用、主体撤销、范围收窄都会在这里直接失败
        return executionContextFactory.rebuild(run.getApplicationId(), subjectType, run.getExternalUserId(), List.of());
    }

    private static void requireLease(AiTaskLeaseDTO lease) {
        if (lease == null
                || lease.getTaskId() == null
                || !StringUtils.hasText(lease.getOwner())
                || lease.getEpoch() < 1) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
