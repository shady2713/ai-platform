package com.basicframework.module.ai.dal.dataobject.webhook;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Webhook 投递尝试留痕（X10）：**只追加**（append-retention），一次完成的尝试一条。
 *
 * <p>为什么单独成表：投递行只有"状态 + 计数"，而"每一次尝试的结论"是故障与安全核验的事实
 * （结论、HTTP 状态、稳定原因码、签名时间戳、耗时）。表内不含目标密钥、投递正文与响应正文；
 * 过量的历史尝试由投递 Job 按保留期分批清理（{@code basic-framework.ai.webhook.attempt-retention}）。
 *
 * <p>模型迁移/落结论在调用中崩溃时可能少一条尝试行：尝试次数以投递行的 {@code attempt_count}
 * 为准（领取时递增），尝试行是已**完成**尝试的结论，不冒充"未完成尝试也有结论"。
 */
@TableName("ai_webhook_delivery_attempt")
@KeySequence("ai_webhook_delivery_attempt_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiWebhookDeliveryAttemptDO extends BaseDO {

    /** 结论：已送达 */
    public static final String OUTCOME_DELIVERED = "DELIVERED";

    /** 结论：可重试（超时、连接失败、5xx、限流、内部错误） */
    public static final String OUTCOME_RETRYABLE = "RETRYABLE";

    /** 结论：确定失败（重定向、4xx、目标未授权/已停用、凭据不可用） */
    public static final String OUTCOME_PERMANENT = "PERMANENT";

    /** 尝试编号 */
    @TableId
    private Long id;

    /** 投递编号 */
    private Long deliveryId;

    /** 第几次尝试（从 1 开始） */
    private Integer attemptNo;

    /** 结论（DELIVERED/RETRYABLE/PERMANENT） */
    private String outcome;

    /** 稳定原因码（送达为空） */
    private String errorCode;

    /** HTTP 状态码（未发出请求时为空） */
    private Integer httpStatus;

    /** 签名时间戳（epoch 秒） */
    private Long signatureTimestamp;

    /** 本次尝试耗时（毫秒） */
    private Long durationMs;

    /** 本次尝试开始时间 */
    private LocalDateTime startedTime;

    /** 本次尝试结束时间 */
    private LocalDateTime finishedTime;
}
