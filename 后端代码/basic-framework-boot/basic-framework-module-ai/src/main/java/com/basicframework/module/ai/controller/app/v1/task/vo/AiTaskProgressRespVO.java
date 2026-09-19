package com.basicframework.module.ai.controller.app.v1.task.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行进度与结果引用（应用端协议层 VO）。
 *
 * <p>只给标识与摘要：读取结果正文仍走会话接口并按当前授权判定，避免"结果引用"变成绕过授权的后门。
 */
@Schema(description = "应用端 - AI 运行进度与结果引用")
@Data
@Accessors(chain = true)
public class AiTaskProgressRespVO {

    @Schema(description = "运行编号")
    private Long runId;

    @Schema(description = "运行业务键")
    private String runKey;

    @Schema(description = "运行状态（ACCEPTED/RUNNING/SUCCEEDED/FAILED/CANCELLED）")
    private String status;

    @Schema(description = "已执行步数")
    private Integer stepCount;

    @Schema(description = "最新事件序号（0 表示还没有事件）")
    private Integer latestSeq;

    @Schema(description = "会话编号")
    private Long conversationId;

    @Schema(description = "结果引用：助手消息编号")
    private Long resultMessageId;

    @Schema(description = "结果引用：助手消息正文摘要")
    private String resultDigest;

    @Schema(description = "任务状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN）")
    private String taskStatus;

    @Schema(description = "任务已尝试次数")
    private Integer attemptCount;

    @Schema(description = "任务下次可尝试时间")
    private LocalDateTime nextAttemptTime;

    @Schema(description = "最近一次失败原因码（稳定词表）")
    private String lastErrorCode;

    @Schema(description = "是否可人工重试")
    private boolean retryable;

    @Schema(description = "不可重试的原因（稳定说明）")
    private String retryBlockedReason;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
