package com.basicframework.module.ai.service.webhook.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Webhook 投递租约（X10）：worker 领取投递后拿到的凭据。
 *
 * <p>{@link #owner} 与 {@link #epoch} 是落结论的栅栏：租约过期被接管后代次递增，
 * 持有旧代次的 worker 命中 0 行，迟到的结论不会覆盖新 worker 的结果。
 */
@Data
@Accessors(chain = true)
public class AiWebhookDeliveryLeaseDTO {

    /** 投递编号 */
    private Long deliveryId;

    /** 租约持有者（worker 标识） */
    private String owner;

    /** 领取纪元（栅栏） */
    private int epoch;

    /** 本次是第几次尝试（1 表示首次；与投递行 attempt_count 一致） */
    private int attempt;
}
