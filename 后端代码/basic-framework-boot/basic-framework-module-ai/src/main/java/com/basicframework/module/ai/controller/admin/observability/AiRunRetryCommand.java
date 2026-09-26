package com.basicframework.module.ai.controller.admin.observability;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TASK_NOT_RETRYABLE;

import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端人工重试（Q03）：**只允许原任务可重试的类型**。
 *
 * <p>与 O06 的按主体重试同一判据（{@link AiRunRetryPolicy}）：结果未知（UNKNOWN）或仍在执行的任务
 * 拒绝普通重试，避免重复产生第二份副作用；成功后任务回到 QUEUED、运行回到 ACCEPTED，
 * 两行各自用乐观锁 CAS 更新，任一步失败整体回滚——不会出现"任务已排队、运行还是 FAILED"的半成品。
 *
 * <p>请求必须带运行行的乐观锁版本：与详情里读到的一致才执行，界面不会拿着过期状态误操作。
 */
@Component
@RequiredArgsConstructor
public class AiRunRetryCommand {

    /** 人工重试重置后的尝试计数（人工决定，重新获得自动重试预算）。 */
    private static final int MANUAL_RETRY_ATTEMPTS = 0;

    private final AiRunMonitorQuery monitorQuery;

    private final AiRunMapper runMapper;

    private final AiRunTaskMapper taskMapper;

    @Transactional(rollbackFor = Exception.class)
    public void retry(Long runId, Integer version) {
        AiRunDO run = monitorQuery.requireRun(runId);
        if (!Objects.equals(version, run.getVersion())) {
            throw exception(AI_STATE_CONFLICT);
        }
        AiRunTaskDO task = monitorQuery.findStepTask(run.getId());
        String blocked = AiRunRetryPolicy.blockedReason(run, task);
        if (blocked != null) {
            throw exception(AI_TASK_NOT_RETRYABLE, blocked);
        }
        int taskVersion = task.getVersion() == null ? 0 : task.getVersion();
        if (taskMapper.updateWithVersion(
                        new AiRunTaskDO()
                                .setId(task.getId())
                                .setStatus(AiRunTaskDO.STATUS_QUEUED)
                                .setAttemptCount(MANUAL_RETRY_ATTEMPTS)
                                .setNextAttemptTime(null)
                                .setLeaseOwner(null)
                                .setLeaseExpiresTime(null)
                                .setVersion(taskVersion + 1),
                        taskVersion)
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
}
