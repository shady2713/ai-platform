package com.basicframework.module.ai.service.tool.action.dto;

import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;

/**
 * 动作创建结果（X06）：新建，或按业务幂等键**复用**已有动作。
 *
 * <p>为什么要把"复用"显式回给调用方：复用意味着**同一业务意图已经在流程中或已产生结论**，
 * 调用方不能再把它当成一次新的调用（不重新发一次性挑战、不重复执行）；
 * 只有新建的场景才存在"待确认"这一步。
 */
public record AiToolActionCreateResult(AiToolActionDO action, boolean reused) {

    public AiToolActionCreateResult {
        if (action == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
    }

    /** 新建（返回待确认动作）。 */
    public static AiToolActionCreateResult created(AiToolActionDO action) {
        return new AiToolActionCreateResult(action, false);
    }

    /** 复用同键动作（同一业务意图已存在）。 */
    public static AiToolActionCreateResult reused(AiToolActionDO action) {
        return new AiToolActionCreateResult(action, true);
    }
}
