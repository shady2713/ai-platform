package com.basicframework.module.ai.controller.admin.webhook.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Webhook 目标分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - Webhook 目标分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiWebhookTargetPageReqVO extends PageParam {

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "目标标识（模糊匹配）")
    private String code;

    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;
}
