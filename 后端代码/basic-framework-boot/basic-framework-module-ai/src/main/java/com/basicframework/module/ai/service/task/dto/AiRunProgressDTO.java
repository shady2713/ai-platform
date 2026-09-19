package com.basicframework.module.ai.service.task.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行进度与结果引用（O06）：按主体查询"跑到哪一步了、结果在哪里、能不能重试"。
 *
 * <p>结果引用只给**标识与摘要**（会话编号、助手消息编号、正文摘要），不复制正文：
 * 读取正文仍走会话接口并按当前授权判定。
 */
@Data
@Accessors(chain = true)
public class AiRunProgressDTO {

    /** 运行编号 */
    private Long runId;

    /** 运行业务键 */
    private String runKey;

    /** 运行状态（ACCEPTED/RUNNING/SUCCEEDED/FAILED/CANCELLED） */
    private String status;

    /** 已执行步数 */
    private Integer stepCount;

    /** 最新事件序号（0 表示还没有事件） */
    private Integer latestSeq;

    /** 会话编号（结果所在的会话） */
    private Long conversationId;

    /** 结果引用：助手消息编号（成功时给出，否则为空） */
    private Long resultMessageId;

    /** 结果引用：助手消息正文摘要（不复制正文） */
    private String resultDigest;

    /** 任务状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN） */
    private String taskStatus;

    /** 任务已尝试次数 */
    private Integer attemptCount;

    /** 任务下次可尝试时间 */
    private LocalDateTime nextAttemptTime;

    /** 最近一次失败原因码（稳定词表） */
    private String lastErrorCode;

    /** 是否可人工重试（当前权限与任务状态都满足时才为真） */
    private boolean retryable;

    /** 不可重试的原因（稳定说明；可重试时为空） */
    private String retryBlockedReason;

    /** 运行创建时间 */
    private LocalDateTime createTime;
}
