package com.basicframework.module.ai.service.task.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 任务租约（O03）：worker 领取任务后拿到的凭据。
 *
 * <p>{@link #owner} 与 {@link #epoch} 是续租与落库的栅栏：租约过期被他人接管后代次递增，
 * 持有旧代次的 worker 既不能续租也不能写终态——迟到的结果不会覆盖新 worker。
 */
@Data
@Accessors(chain = true)
public class AiTaskLeaseDTO {

    /** 任务编号 */
    private Long taskId;

    /** 运行编号 */
    private Long runId;

    /** 任务类型 */
    private String taskKind;

    /** 租约持有者（worker 标识） */
    private String owner;

    /** 领取代次（栅栏） */
    private int epoch;

    /** 本次领取是第几次尝试（1 表示首次） */
    private int attempt;
}
