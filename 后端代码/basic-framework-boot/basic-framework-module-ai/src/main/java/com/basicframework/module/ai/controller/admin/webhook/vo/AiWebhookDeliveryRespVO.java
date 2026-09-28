package com.basicframework.module.ai.controller.admin.webhook.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Webhook 投递响应（协议层 VO）：只有状态、计数与稳定原因码，**不含投递正文、目标地址与密钥**。
 */
@Schema(description = "管理后台 - Webhook 投递")
@Data
@Accessors(chain = true)
public class AiWebhookDeliveryRespVO {

    @Schema(description = "投递编号（行主键）")
    private Long id;

    @Schema(description = "投递编号（对外唯一，重试不变；接收端据此去重）")
    private String deliveryNo;

    @Schema(description = "投递目标编号")
    private Long targetId;

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "资源类型（RUN）")
    private String resourceType;

    @Schema(description = "资源编号（运行编号）")
    private Long resourceId;

    @Schema(description = "资源业务键")
    private String resourceKey;

    @Schema(description = "事件发生时间")
    private LocalDateTime occurredTime;

    @Schema(description = "正文摘要（sha-256；正文本身不返回）")
    private String payloadDigest;

    @Schema(description = "状态（PENDING/RUNNING/SUCCEEDED/FAILED）")
    private String status;

    @Schema(description = "已尝试次数")
    private Integer attemptCount;

    @Schema(description = "最大尝试次数")
    private Integer maxAttempts;

    @Schema(description = "最近一次尝试的稳定原因码")
    private String lastErrorCode;

    @Schema(description = "终态失败码（重试预算耗尽或确定失败原因；成功为空）")
    private String failureCode;

    @Schema(description = "下次可领取时间")
    private LocalDateTime nextAttemptTime;

    @Schema(description = "首次尝试时间")
    private LocalDateTime firstAttemptTime;

    @Schema(description = "投递成功时间")
    private LocalDateTime deliveredTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
