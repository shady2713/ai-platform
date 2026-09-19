package com.basicframework.module.ai.service.run;

import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.service.run.dto.AiRunExecutionResultDTO;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;

/**
 * 文本运行执行器（O04）：把一次已受理的运行执行到终态。
 *
 * <p>调用方（worker）先按 O03 领取任务拿到租约，再调用本接口；执行过程不持有长事务，
 * 身份在每次执行前重建，模型与工具调用都在事务之外进行。
 */
public interface AiRunExecutionService {

    /** 执行一次已领取的文本运行（预算为空时使用平台默认值）。 */
    AiRunExecutionResultDTO execute(AiTaskLeaseDTO lease, AiRunBudget budget);
}
