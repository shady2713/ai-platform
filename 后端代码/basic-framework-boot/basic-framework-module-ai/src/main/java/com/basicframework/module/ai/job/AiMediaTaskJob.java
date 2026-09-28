package com.basicframework.module.ai.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.enums.AiErrorCodeRanges;
import com.basicframework.module.ai.service.media.AiMediaStepExecutor;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 媒体任务 Job（X03）：常驻消费者——先恢复过期租约，再领取待处理任务，逐个交给匹配的执行器。
 *
 * <p>职责边界：
 * <ul>
 *   <li>本 Job 只做**编排**：领取（CAS）、分发、写终态；实际调用与产物落库在执行器里（{@link AiMediaStepExecutor}）；</li>
 *   <li>没有执行器承接该操作时，任务以稳定原因码 {@code executor-unavailable} 失败，
 *       不假装成功、也不把任务永远留在 RUNNING；</li>
 *   <li>执行异常统一收敛为稳定原因码（平台错误码或 {@code execution-failed}），只落码不落上游报文；</li>
 *   <li>写终态带 (owner, epoch) 栅栏：租约已被接管时结果被丢弃（{@code finish} 返回 false），
 *       由新 worker 的事实说了算。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiMediaTaskJob implements JobHandler {

    /** 没有执行器承接该操作时的稳定原因码。 */
    static final String REASON_EXECUTOR_UNAVAILABLE = "executor-unavailable";

    /** 执行抛出未归类异常时的稳定原因码（不含异常正文）。 */
    static final String REASON_EXECUTION_FAILED = "execution-failed";

    /** 服务异常转出的失败原因码前缀（平台错误码 {@code 1_003_xxx_xxx} 的十进制形式）。 */
    private static final String ERROR_CODE_PREFIX = String.valueOf(AiErrorCodeRanges.AI_PREFIX);

    private final AiMediaTaskService taskService;

    private final ObjectProvider<AiMediaStepExecutor> executors;

    /** worker 标识：实例内唯一（进程启动时生成），租约栅栏据此区分持有者。 */
    private final String workerId;

    private final int batchSize;

    private final int leaseSeconds;

    private final int retryDelaySeconds;

    private final int recoverLimit;

    /** 构造器注入：配置与依赖都通过构造器传入。 */
    public AiMediaTaskJob(
            AiMediaTaskService taskService,
            ObjectProvider<AiMediaStepExecutor> executors,
            @Value("${basic-framework.ai.media.worker-prefix:media-task}") String workerPrefix,
            @Value("${basic-framework.ai.media.batch-size:5}") int batchSize,
            @Value("${basic-framework.ai.media.lease-seconds:300}") int leaseSeconds,
            @Value("${basic-framework.ai.media.retry-delay-seconds:30}") int retryDelaySeconds,
            @Value("${basic-framework.ai.media.recover-limit:50}") int recoverLimit) {
        this.taskService = taskService;
        this.executors = executors;
        this.workerId = workerPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.retryDelaySeconds = retryDelaySeconds;
        this.recoverLimit = recoverLimit;
    }

    @Override
    public String execute(String param) {
        int recovered = taskService.recoverExpiredLeases(retryDelaySeconds, recoverLimit);
        List<AiMediaTaskLeaseDTO> leases =
                taskService.claim(workerId, Math.max(1, batchSize), Math.max(1, leaseSeconds));
        int succeeded = 0;
        int failed = 0;
        for (AiMediaTaskLeaseDTO lease : leases) {
            AiMediaStepOutcome outcome = runOnce(lease);
            if (taskService.finish(lease, outcome)) {
                if (AiMediaTaskDO.STATUS_SUCCEEDED.equals(outcome.getStatus())) {
                    succeeded++;
                } else {
                    failed++;
                }
            }
        }
        return "recovered=" + recovered + ",claimed=" + leases.size() + ",succeeded=" + succeeded + ",failed=" + failed;
    }

    private AiMediaStepOutcome runOnce(AiMediaTaskLeaseDTO lease) {
        AiMediaTaskDO task = taskService.getTaskForExecution(lease.getTaskId());
        if (task == null) {
            // 任务在领取后被删除：以失败原因码收尾，避免留下无主 RUNNING 行
            return AiMediaStepOutcome.failed(REASON_EXECUTION_FAILED);
        }
        AiMediaStepExecutor executor = executors.stream()
                .filter(candidate -> candidate.supports(task.getOperation()))
                .findFirst()
                .orElse(null);
        if (executor == null) {
            return AiMediaStepOutcome.failed(REASON_EXECUTOR_UNAVAILABLE);
        }
        try {
            AiMediaStepOutcome outcome = executor.execute(lease, task);
            return outcome == null ? AiMediaStepOutcome.failed(REASON_EXECUTION_FAILED) : outcome;
        } catch (Exception exception) {
            // 只落稳定原因码或平台错误码：不落上游报文、不落异常正文
            return AiMediaStepOutcome.failed(reasonOf(exception));
        }
    }

    /** 失败原因：平台服务异常取其错误码，其它异常收敛为 {@code execution-failed}。 */
    private static String reasonOf(Exception exception) {
        if (exception instanceof com.basicframework.framework.common.exception.ServiceException serviceException) {
            String code = String.valueOf(serviceException.getCode());
            return code.startsWith(ERROR_CODE_PREFIX) ? code : REASON_EXECUTION_FAILED;
        }
        return REASON_EXECUTION_FAILED;
    }
}
