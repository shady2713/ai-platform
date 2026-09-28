package com.basicframework.module.ai.controller.admin.webhook.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Webhook 投递分页查询（协议层 VO；死信查看：按状态过滤 FAILED）。 */
@Schema(description = "管理后台 - Webhook 投递分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiWebhookDeliveryPageReqVO extends PageParam {

    @Schema(description = "投递目标编号")
    private Long targetId;

    @Schema(description = "状态（PENDING/RUNNING/SUCCEEDED/FAILED）")
    private String status;

    @Schema(description = "事件类型")
    private String eventType;
}
