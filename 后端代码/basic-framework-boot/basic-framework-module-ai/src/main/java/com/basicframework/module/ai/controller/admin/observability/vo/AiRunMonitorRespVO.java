package com.basicframework.module.ai.controller.admin.observability.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行监控列表行（Q03，协议层 VO）。
 *
 * <p>只给运维需要的**标识与状态**：不含提示词、响应正文、外部用户标识明文与任何凭据
 * （AT-011：后台只能看到 configured 类标识）。可重试性与其原因与重试命令同源，
 * 避免"界面能点、接口拒绝"。
 */
@Schema(description = "管理后台 - 运行监控列表行")
@Data
@Accessors(chain = true)
public class AiRunMonitorRespVO {

    @Schema(description = "运行编号")
    private Long runId;

    @Schema(description = "运行业务键（主体内唯一）")
    private String runKey;

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "服务编号")
    private Long serviceId;

    @Schema(description = "受理时固定的发布版本编号")
    private Long releaseId;

    @Schema(description = "主体类型（APP/USER；不返回主体标识明文）")
    private String subjectType;

    @Schema(description = "运行状态（ACCEPTED/RUNNING/SUCCEEDED/FAILED/CANCELLED）")
    private String status;

    @Schema(description = "已执行步数（有界执行）")
    private Integer stepCount;

    @Schema(description = "已分配的事件序号（0 表示还没有事件）")
    private Integer latestSeq;

    @Schema(description = "任务状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN）")
    private String taskStatus;

    @Schema(description = "任务已尝试次数")
    private Integer attemptCount;

    @Schema(description = "最近一次失败原因码（稳定词表，不含异常正文）")
    private String lastErrorCode;

    @Schema(description = "是否可人工重试（与重试命令同一判据）")
    private boolean retryable;

    @Schema(description = "不可重试的原因（稳定说明；可重试时为空）")
    private String retryBlockedReason;

    @Schema(description = "受理时间")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间（终态时间或最近一次状态变更）")
    private LocalDateTime updateTime;
}
