package com.basicframework.module.ai.service.task;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TASK_NOT_RETRYABLE;

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
import com.basicframework.module.ai.service.authorization.AiExecutionContextFactory;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.task.dto.AiRetentionCleanupResultDTO;
import com.basicframework.module.ai.service.task.dto.AiRunProgressDTO;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import java.time.Duration;
import java.time.LocalDateTime;
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

    /** 人工重试重置后的尝试计数（人工决定，重新获得自动重试预算）。 */
    private static final int MANUAL_RETRY_ATTEMPTS = 0;

    /** 保留期清理的单批上限（与恢复扫描同一量级，避免长事务）。 */
    private static final int MAX_CLEANUP_BATCH = 1_000;

    private final AiTaskClaimMapper claimMapper;

    private final AiRetentionCleanupMapper cleanupMapper;

    private final AiRunMapper runMapper;

    private final AiRunTaskMapper taskMapper;

    private final AiRunEventMapper eventMapper;

    private final AiConversationMessageMapper messageMapper;

    private final AiExecutionContextFactory executionContextFactory;

    private final AiConversationSubjectResolver subjectResolver;

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

    @Override
    public AiRunProgressDTO progress(Long runId) {
        AiRunDO run = requireOwnedRun(runId);
        AiRunTaskDO task = taskMapper.selectByRunAndKind(run.getId(), AiRunTaskDO.KIND_RUN_STEP);
        AiConversationMessageDO result = resultMessage(run);
        String blocked = retryBlockedReason(run, task);
        return new AiRunProgressDTO()
                .setRunId(run.getId())
                .setRunKey(run.getRunKey())
                .setStatus(run.getStatus())
                .setStepCount(run.getStepCount())
                .setLatestSeq(run.getEventSeq() == null ? 0 : run.getEventSeq())
                .setConversationId(run.getConversationId())
                .setResultMessageId(result == null ? null : result.getId())
                .setResultDigest(result == null ? null : result.getContentHash())
                .setTaskStatus(task == null ? null : task.getStatus())
                .setAttemptCount(task == null ? null : task.getAttemptCount())
                .setNextAttemptTime(task == null ? null : task.getNextAttemptTime())
                .setLastErrorCode(task == null ? null : task.getLastErrorCode())
                .setRetryable(blocked == null)
                .setRetryBlockedReason(blocked)
                .setCreateTime(run.getCreateTime());
    }

    @Override
    public PageResult<AiRunProgressDTO> pageProgress(PageParam pageParam) {
        AiConversationSubject subject = subjectResolver();
        PageResult<AiRunDO> page = runMapper.selectPageBySubject(
                pageParam, subject.applicationId(), subject.subjectTypeName(), subject.externalUserId());
        List<AiRunProgressDTO> list = new ArrayList<>();
        for (AiRunDO run : page.getList()) {
            list.add(progress(run.getId()));
        }
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retry(Long runId, Integer version) {
        AiRunDO run = requireOwnedRun(runId);
        // 人工重试前重建当前身份与授权：失权、范围收窄、应用停用都在这里拒绝
        rebuildIdentity(run.getId());
        AiRunTaskDO task = taskMapper.selectByRunAndKind(run.getId(), AiRunTaskDO.KIND_RUN_STEP);
        String blocked = retryBlockedReason(run, task);
        if (blocked != null) {
            throw exception(AI_TASK_NOT_RETRYABLE, blocked);
        }
        int current = task.getVersion() == null ? 0 : task.getVersion();
        if (taskMapper.updateWithVersion(
                        new AiRunTaskDO()
                                .setId(task.getId())
                                .setStatus(AiRunTaskDO.STATUS_QUEUED)
                                .setAttemptCount(MANUAL_RETRY_ATTEMPTS)
                                .setNextAttemptTime(null)
                                .setLeaseOwner(null)
                                .setLeaseExpiresTime(null)
                                .setVersion(current + 1),
                        current)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        int runVersion = run.getVersion() == null ? 0 : run.getVersion();
        if (runMapper.updateWithVersion(
                        new AiRunDO()
                                .setId(run.getId())
                                .setStatus(AiRunDO.STATUS_ACCEPTED)
                                .setVersion(runVersion + 1),
                        runVersion)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiRetentionCleanupResultDTO cleanup(Duration retention, int batchSize, int maxBatches) {
        Duration window =
                retention == null || retention.isZero() || retention.isNegative() ? Duration.ofDays(30) : retention;
        int batch = Math.min(Math.max(batchSize, 1), MAX_CLEANUP_BATCH);
        int batches = Math.max(maxBatches, 1);
        LocalDateTime before = LocalDateTime.now().minus(window);
        AiRetentionCleanupResultDTO result = new AiRetentionCleanupResultDTO();
        for (int round = 0; round < batches; round++) {
            // 顺序与引用方向一致：事件 → 任务 → 运行 → 消息 → 会话；每步都带"仍被引用则不删"的守卫
            int events = cleanupMapper.deleteEventsOfTerminalRuns(before, batch);
            int tasks = cleanupMapper.deleteTasksOfTerminalRuns(before, batch);
            // 幂等记录引用运行（外键 RESTRICT）：必须先于运行行清理
            int idempotency = cleanupMapper.deleteIdempotencyOfTerminalRuns(before, batch);
            int runs = cleanupMapper.deleteTerminalRuns(before, batch);
            int messages = cleanupMapper.deleteMessagesOfClosedConversations(before, batch);
            int conversations = cleanupMapper.deleteClosedConversations(before, batch);
            result.setEvents(result.getEvents() + events)
                    .setTasks(result.getTasks() + tasks)
                    .setIdempotency(result.getIdempotency() + idempotency)
                    .setRuns(result.getRuns() + runs)
                    .setMessages(result.getMessages() + messages)
                    .setConversations(result.getConversations() + conversations);
            if (events + tasks + idempotency + runs + messages + conversations == 0) {
                break;
            }
        }
        return result;
    }

    /** 结果引用：该运行写入的助手消息（成功时才有）。 */
    private AiConversationMessageDO resultMessage(AiRunDO run) {
        if (run.getConversationId() == null || !AiRunDO.STATUS_SUCCEEDED.equals(run.getStatus())) {
            return null;
        }
        return messageMapper.selectAll(run.getConversationId()).stream()
                .filter(message -> AiConversationMessageDO.ROLE_ASSISTANT.equals(message.getRole()))
                .filter(message -> run.getId().equals(message.getSourceRunId()))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /** 不可重试的原因；返回 null 表示可重试。 */
    private String retryBlockedReason(AiRunDO run, AiRunTaskDO task) {
        if (task == null) {
            return "缺少可重试的任务";
        }
        if (AiRunTaskDO.STATUS_UNKNOWN.equals(task.getStatus())) {
            // 结果未知：重复执行可能产生第二份副作用，必须人工核对后新建运行
            return "结果未知（UNKNOWN），请核对后新建运行";
        }
        if (AiRunTaskDO.STATUS_RUNNING.equals(task.getStatus())) {
            return "任务仍在执行（或租约未过期）";
        }
        if (AiRunTaskDO.STATUS_SUCCEEDED.equals(task.getStatus())) {
            return "任务已成功";
        }
        if (!AiRunTaskDO.STATUS_FAILED.equals(task.getStatus())) {
            return "任务状态为 " + task.getStatus();
        }
        if (AiRunDO.STATUS_SUCCEEDED.equals(run.getStatus()) || AiRunDO.STATUS_CANCELLED.equals(run.getStatus())) {
            return "运行已结束（" + run.getStatus() + "）";
        }
        return null;
    }

    private AiRunDO requireOwnedRun(Long runId) {
        AiConversationSubject subject = subjectResolver();
        AiRunDO run = runId == null
                ? null
                : runMapper.selectOwned(
                        runId, subject.applicationId(), subject.subjectTypeName(), subject.externalUserId());
        if (run == null) {
            throw exception(AI_RUN_NOT_FOUND);
        }
        return run;
    }

    private AiConversationSubject subjectResolver() {
        return subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_RUN_NOT_FOUND));
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
