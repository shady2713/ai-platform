package com.basicframework.module.ai.controller.admin.webhook.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Webhook 目标响应（协议层 VO）：**不含**签名密钥的明文与密文，只给"是否已配置"与版本号。
 */
@Schema(description = "管理后台 - Webhook 目标")
@Data
@Accessors(chain = true)
public class AiWebhookTargetRespVO {

    @Schema(description = "目标编号")
    private Long id;

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "目标标识（创建后不可修改）")
    private String code;

    @Schema(description = "目标名称")
    private String name;

    @Schema(description = "投递地址")
    private String targetUrl;

    @Schema(description = "事件白名单")
    private List<String> eventTypes;

    @Schema(description = "是否已配置签名密钥（不返回密钥本身）")
    @ToString.Exclude
    private Boolean secretConfigured;

    @Schema(description = "签名密钥版本（0 表示未配置）")
    @ToString.Exclude
    private Integer secretRevision;

    @Schema(description = "状态（ENABLED/DISABLED；停用即停发）")
    private String status;

    @Schema(description = "单次投递的最大尝试次数（有界重试）")
    private Integer maxAttempts;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
