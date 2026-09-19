package com.basicframework.module.ai.service.run.dto;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 运行执行结果（O04）：只给出终态、步数与稳定原因，不携带提示词、模型输出正文或凭据。
 *
 * <p>终态写入是原子的：结果 DTO 里的状态与库中状态一致，晚到的回调无法覆盖（由任务租约栅栏与
 * 运行行乐观锁共同保证）。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"outputText"})
public class AiRunExecutionResultDTO {

    /** 运行编号 */
    private Long runId;

    /** 运行终态（SUCCEEDED/FAILED/CANCELLED） */
    private String status;

    /** 已执行步数 */
    private int steps;

    /** 工具调用次数 */
    private int toolCalls;

    /** 总耗时（毫秒） */
    private long durationMillis;

    /** 失败原因码（稳定词表；成功时为空） */
    private String errorCode;

    /** 模型可见输出（用于写入会话消息；不进日志与 toString） */
    private String outputText;
}
