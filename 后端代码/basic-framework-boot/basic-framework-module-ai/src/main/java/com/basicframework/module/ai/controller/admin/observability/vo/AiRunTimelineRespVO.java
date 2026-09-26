package com.basicframework.module.ai.controller.admin.observability.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行事件时间线（Q03，协议层 VO）：**默认不展开敏感正文**。
 *
 * <p>事件块正文（{@code blockJson}）可能含模型输出或业务数据，运维列表只给"有没有块、
 * 什么类型"，正文不进列表接口；需要正文的场景走受权接口（会话消息 / 报表读取）。
 */
@Schema(description = "管理后台 - 运行事件时间线")
@Data
@Accessors(chain = true)
public class AiRunTimelineRespVO {

    @Schema(description = "运行内事件序号（从 1 递增）")
    private Integer seq;

    @Schema(description = "事件状态（QUEUED/RUNNING/WAITING_INPUT/WAITING_CONFIRMATION/SUCCEEDED/FAILED/CANCELLED）")
    private String status;

    @Schema(description = "结果块类型（受控结构；无块时为空）")
    private String blockType;

    @Schema(description = "事件契约版本（RunEvent v1）")
    private String schemaVersion;

    @Schema(description = "是否带结果块正文（正文本身不在本接口返回）")
    private boolean blockPresent;

    @Schema(description = "事件写入时间")
    private LocalDateTime createTime;
}
