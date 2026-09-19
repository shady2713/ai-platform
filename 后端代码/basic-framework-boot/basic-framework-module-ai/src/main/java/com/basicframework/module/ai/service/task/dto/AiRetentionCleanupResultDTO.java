package com.basicframework.module.ai.service.task.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 保留期清理结果（O06）：按类别给出实际清理条数，便于审计与批次续跑。 */
@Data
@Accessors(chain = true)
public class AiRetentionCleanupResultDTO {

    /** 清理的事件条数 */
    private int events;

    /** 清理的任务条数 */
    private int tasks;

    /** 清理的幂等记录条数 */
    private int idempotency;

    /** 清理的运行条数 */
    private int runs;

    /** 清理的消息条数 */
    private int messages;

    /** 清理的会话条数 */
    private int conversations;

    /** 合计条数 */
    public int total() {
        return events + tasks + idempotency + runs + messages + conversations;
    }
}
