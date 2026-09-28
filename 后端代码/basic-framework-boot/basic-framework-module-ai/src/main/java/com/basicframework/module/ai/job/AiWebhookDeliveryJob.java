package com.basicframework.module.ai.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.service.webhook.AiWebhookDeliveryOutcome;
import com.basicframework.module.ai.service.webhook.AiWebhookDeliverySender;
import com.basicframework.module.ai.service.webhook.AiWebhookDeliveryService;
import com.basicframework.module.ai.service.webhook.AiWebhookFailureCodes;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookDeliveryLeaseDTO;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Webhook 投递 Job（X10）：常驻消费者——补漏入队 → 恢复过期租约 → 领取 → 投递 → 落结论 → 清理留痕。
 *
 * <p>职责边界（与"投递失败绝不影响运行结果"直接相关）：
 * <ul>
 *   <li>本 Job **只读写投递与尝试两张表**：既不修改运行/任务状态，也不重新发起运行——
 *       投递只是"把已有结果送出去"，送不出去就是投递失败这一件事；</li>
 *   <li>领取是 CAS + 租约，落结论带 (owner, epoch) 栅栏：租约被接管后旧结论被丢弃；</li>
 *   <li>崩溃留下的 RUNNING 由租约过期恢复：未达上限回队列、达上限置死信（有界收敛，不需要人工干预）；</li>
 *   <li>单条投递的异常不影响整批：异常收敛为稳定原因码（可重试），循环继续处理后面的投递；</li>
 *   <li>返回的摘要只含计数，不含目标地址、投递正文与密钥。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiWebhookDeliveryJob implements JobHandler {

    private final AiWebhookDeliveryService deliveryService;

    private final AiWebhookDeliverySender sender;

    /** worker 标识：实例内唯一（进程启动时生成），租约栅栏据此区分持有者。 */
    private final String workerId;

    private final int batchSize;

    private final int leaseSeconds;

    private final int retryDelaySeconds;

    private final int recoverLimit;

    private final int enqueueLimit;

    private final int pruneLimit;

    private final Duration attemptRetention;

    /** 构造器注入：编排参数全部来自配置，默认值对应"10 秒一轮、单轮 5 条、租约 60 秒"。 */
    public AiWebhookDeliveryJob(
            AiWebhookDeliveryService deliveryService,
            AiWebhookDeliverySender sender,
            @Value("${basic-framework.ai.webhook.worker-prefix:webhook-delivery}") String workerPrefix,
            @Value("${basic-framework.ai.webhook.batch-size:5}") int batchSize,
            @Value("${basic-framework.ai.webhook.lease-seconds:60}") int leaseSeconds,
            @Value("${basic-framework.ai.webhook.retry-delay-seconds:10}") int retryDelaySeconds,
            @Value("${basic-framework.ai.webhook.recover-limit:50}") int recoverLimit,
            @Value("${basic-framework.ai.webhook.enqueue-limit:50}") int enqueueLimit,
            @Value("${basic-framework.ai.webhook.prune-limit:200}") int pruneLimit,
            @Value("${basic-framework.ai.webhook.attempt-retention:P30D}") Duration attemptRetention) {
        this.deliveryService = deliveryService;
        this.sender = sender;
        this.workerId = workerPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.retryDelaySeconds = retryDelaySeconds;
        this.recoverLimit = recoverLimit;
        this.enqueueLimit = enqueueLimit;
        this.pruneLimit = pruneLimit;
        this.attemptRetention = attemptRetention;
    }

    @Override
    public String execute(String param) {
        int recovered = deliveryService.recoverExpiredLeases(retryDelaySeconds, recoverLimit);
        int enqueued = deliveryService.enqueueTerminalRuns(enqueueLimit);
        List<AiWebhookDeliveryLeaseDTO> leases =
                deliveryService.claim(workerId, Math.max(1, batchSize), Math.max(1, leaseSeconds));
        int delivered = 0;
        int retried = 0;
        int failed = 0;
        for (AiWebhookDeliveryLeaseDTO lease : leases) {
            AiWebhookDeliveryOutcome outcome = deliverOnce(lease);
            if (outcome == null) {
                continue;
            }
            if (outcome.isDelivered()) {
                delivered++;
            } else if (outcome.isRetryable()) {
                retried++;
            } else {
                failed++;
            }
        }
        int pruned = deliveryService.pruneAttempts(attemptRetention, pruneLimit);
        return "recovered=" + recovered + ",enqueued=" + enqueued + ",claimed=" + leases.size() + ",delivered="
                + delivered + ",retried=" + retried + ",failed=" + failed + ",pruned=" + pruned;
    }

    /**
     * 投递一条并落结论；返回 null 表示这条投递本轮没有产生结论（已被接管、投递行消失或落库失败）。
     * 异常在这里收敛：单条投递的任何失败都不会中断整批。
     */
    private AiWebhookDeliveryOutcome deliverOnce(AiWebhookDeliveryLeaseDTO lease) {
        AiWebhookDeliveryDO delivery = deliveryService.getForExecution(lease.getDeliveryId());
        if (delivery == null || !AiWebhookDeliveryDO.STATUS_RUNNING.equals(delivery.getStatus())) {
            // 投递行被清理，或租约已被接管：本轮不再投递（不允许重复发出同一事件）
            return null;
        }
        AiWebhookDeliveryOutcome outcome;
        try {
            outcome = sender.deliver(delivery);
        } catch (RuntimeException exception) {
            // 兜底：投递器已经内部收敛，这里只兜"意料之外的运行时异常"，只落稳定原因码
            log.warn("Webhook 投递 {} 执行异常（原因已脱敏）", delivery.getDeliveryNo());
            outcome = AiWebhookDeliveryOutcome.retryable(AiWebhookFailureCodes.INTERNAL_ERROR, null, null, 0L);
        }
        try {
            if (!deliveryService.finish(lease, outcome)) {
                // 栅栏未命中：结论已被接管者写入，本次不计数（事实以接管者为准）
                return null;
            }
        } catch (RuntimeException exception) {
            // 落库失败（连接抖动等）：投递行留在 RUNNING，由租约过期恢复继续（有界）
            log.warn("Webhook 投递 {} 结论落库失败（原因已脱敏）", delivery.getDeliveryNo());
            return null;
        }
        return outcome;
    }
}
