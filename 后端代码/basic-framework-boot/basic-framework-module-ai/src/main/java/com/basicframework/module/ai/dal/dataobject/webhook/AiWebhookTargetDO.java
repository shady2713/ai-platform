package com.basicframework.module.ai.dal.dataobject.webhook;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 受控结果 Webhook 目标（X10）：控制面配置，属于某个 AI 应用。
 *
 * <p>密钥边界：HMAC 签名密钥只以 {@link #secretCiphertext}（CredentialCipher 密文，AAD 绑本行编号）
 * 形式存在，轮换只递增 {@link #secretRevision}；任何接口与日志都不回显明文，也不回显密文。
 * 目标停用（{@code DISABLED}）即停发：入队不再产生新投递，人工重投被拒；在途投递按当时事实收尾。
 *
 * <p>事件白名单（{@link #eventTypes}）是 JSON 数组文本，取值见
 * {@code com.basicframework.module.ai.service.webhook.AiWebhookEventTypes}。
 */
@TableName("ai_webhook_target")
@KeySequence("ai_webhook_target_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiWebhookTargetDO extends SoftDeletableDO {

    /** 状态：启用（参与投递） */
    public static final String STATUS_ENABLED = "ENABLED";

    /** 状态：停用（不再产生投递；在途投递按事实收尾） */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 默认最大尝试次数 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * 补漏扫描水位的起点：新建（或重新订阅事件）的目标从最早的历史开始逐段补齐，
     * 而不是"只发从创建时刻起的新事件"——补漏语义不变，只是把一次全表扫描摊成每轮有界的一段。
     */
    public static final LocalDateTime ENQUEUE_WATERMARK_EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0, 0);

    /** 投递目标编号 */
    @TableId
    private Long id;

    /** 应用编号（只投递该应用下运行的终态结果） */
    private Long applicationId;

    /** 目标标识（应用内唯一，创建后不可修改） */
    private String code;

    /** 目标名称 */
    private String name;

    /** 投递地址 */
    private String targetUrl;

    /** 事件白名单（JSON 数组文本） */
    private String eventTypes;

    /** HMAC 签名密钥密文（永不回显、不进日志） */
    @ToString.Exclude
    private String secretCiphertext;

    /** 签名密钥版本（轮换递增） */
    @ToString.Exclude
    private Integer secretRevision;

    /** 状态（ENABLED/DISABLED） */
    private String status;

    /** 单次投递的最大尝试次数（有界重试） */
    private Integer maxAttempts;

    /**
     * 补漏扫描水位：该目标**已覆盖**的终态运行更新时间上界（左闭右开区间的右端）。
     * 只由投递 Job 单调推进，不参与乐观锁（它是作业的内部进度，不是控制面版本）；
     * 推进由 {@code AiWebhookTargetMapper#advanceEnqueueWatermark} 单条 UPDATE 完成。
     */
    private LocalDateTime enqueueWatermark;

    /** 乐观锁版本 */
    private Integer version;
}
