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
 * 受控结果 Webhook 投递（X10）：**事实行**，一次「目标 × 事件 × 资源」最多一条（唯一键兜底）。
 *
 * <p>与运行结果解耦：投递只读运行事实，失败绝不回写运行状态、绝不重跑运行。状态只按
 * {@code PENDING → RUNNING → SUCCEEDED/FAILED} 迁移，领取是 CAS（写 owner/epoch/租约），
 * 续租与落结论都带 (owner, epoch) 栅栏——租约被接管后旧 worker 命中 0 行。
 *
 * <p>正文（{@link #payloadJson}）在入队时确定并冻结：重试复用同一份字节，
 * 因此正文摘要与签名覆盖的内容在重试之间保持一致；正文只含事件类型、资源引用与运行状态，
 * 不含提示词、响应正文、上游地址与凭据。
 */
@TableName("ai_webhook_delivery")
@KeySequence("ai_webhook_delivery_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiWebhookDeliveryDO extends SoftDeletableDO {

    /** 状态：待投递（含退避等待中的重试） */
    public static final String STATUS_PENDING = "PENDING";

    /** 状态：投递中（持有租约） */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：已送达（2xx） */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 状态：失败（重试预算耗尽或确定失败；死信可人工重投） */
    public static final String STATUS_FAILED = "FAILED";

    /** 资源类型：运行（当前唯一取值） */
    public static final String RESOURCE_RUN = "RUN";

    /** 投递编号（行主键） */
    @TableId
    private Long id;

    /** 投递编号（对外唯一，重试不变；接收端据此去重） */
    private String deliveryNo;

    /** 投递目标编号 */
    private Long targetId;

    /** 应用编号（入队时快照） */
    private Long applicationId;

    /** 事件类型（RUN.SUCCEEDED/RUN.FAILED/RUN.CANCELLED） */
    private String eventType;

    /** 资源类型（RUN） */
    private String resourceType;

    /** 资源编号（运行编号） */
    private Long resourceId;

    /** 资源业务键（run_ 前缀） */
    private String resourceKey;

    /** 事件发生时间（运行终态写入时间，入队时快照） */
    private java.time.LocalDateTime occurredTime;

    /** 投递正文（规范化 JSON，入队时冻结；重试复用同一份字节） */
    private String payloadJson;

    /** 正文摘要（sha-256 十六进制） */
    private String payloadDigest;

    /** 状态（PENDING/RUNNING/SUCCEEDED/FAILED） */
    private String status;

    /** 已尝试次数（领取时递增） */
    private Integer attemptCount;

    /** 最大尝试次数（入队时从目标快照） */
    private Integer maxAttempts;

    /** 下次可领取时间（退避后） */
    private LocalDateTime nextAttemptTime;

    /** 租约持有者（worker 标识） */
    private String leaseOwner;

    /** 租约到期时间 */
    private LocalDateTime leaseExpiresTime;

    /** 最近一次心跳时间 */
    private LocalDateTime heartbeatTime;

    /** 领取纪元（栅栏：每次领取 +1） */
    private Integer claimedEpoch;

    /** 最近一次尝试的稳定原因码（不含上游正文） */
    private String lastErrorCode;

    /** 终态失败码（重试预算耗尽或确定失败原因；成功为空） */
    private String failureCode;

    /** 首次尝试时间 */
    private LocalDateTime firstAttemptTime;

    /** 投递成功时间 */
    private LocalDateTime deliveredTime;

    /** 乐观锁版本 */
    private Integer version;
}
