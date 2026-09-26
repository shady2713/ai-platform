package com.basicframework.module.ai.controller.admin.observability.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行监控详情（Q03，协议层 VO）。
 *
 * <p>回答四个运维问题：**跑到哪一步**（步骤/事件序号）、**失败原因**（稳定原因码）、
 * **慢在哪里**（耗时分解，未知计量如实标注）、**能不能重试**（同一判据 + 阻塞原因）。
 * 只给标识与版本证据（发布版本、端点配置修订、内容摘要），不含提示词、响应正文与凭据。
 */
@Schema(description = "管理后台 - 运行监控详情")
@Data
@Accessors(chain = true)
public class AiRunMonitorDetailRespVO {

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

    @Schema(description = "受理时声明的数据分级（L1_PUBLIC/L2_INTERNAL/L3_PERSONAL/L4_SECRET）")
    private String dataLevel;

    @Schema(description = "受理时固定的模型端点编号（配置与凭据仍走端点接口，此处只给标识）")
    private Long modelEndpointId;

    @Schema(description = "受理时固定的端点配置版本")
    private Integer endpointConfigRevision;

    @Schema(description = "受理时固定的发布内容摘要")
    private String contentHash;

    @Schema(description = "结果所在会话编号（成功时给出）")
    private Long conversationId;

    @Schema(description = "结果引用：助手消息编号（成功时给出，但不复制正文）")
    private Long resultMessageId;

    @Schema(description = "结果引用：助手消息内容摘要")
    private String resultDigest;

    @Schema(description = "任务编号")
    private Long taskId;

    @Schema(description = "任务类型（RUN_STEP）")
    private String taskKind;

    @Schema(description = "任务状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN）")
    private String taskStatus;

    @Schema(description = "任务已尝试次数")
    private Integer attemptCount;

    @Schema(description = "任务下次可尝试时间（退避中才有）")
    private LocalDateTime nextAttemptTime;

    @Schema(description = "最近一次失败原因码（稳定词表，不含异常正文）")
    private String lastErrorCode;

    @Schema(description = "运行行乐观锁版本（重试请求必须带上，避免界面拿过期版本操作）")
    private Integer runVersion;

    @Schema(description = "耗时分解（未知计量如实标注，不写 0）")
    private AiRunTimingRespVO timing;

    @Schema(description = "是否可人工重试（与重试命令同一判据）")
    private boolean retryable;

    @Schema(description = "不可重试的原因（稳定说明；可重试时为空）")
    private String retryBlockedReason;

    @Schema(description = "受理时间")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;
}
