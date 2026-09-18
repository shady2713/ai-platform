package com.basicframework.module.ai.service.authorization;

import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import java.util.List;

/**
 * 执行上下文重建（A06）：为异步任务与后续步骤按 app/subject 重新解析身份与范围。
 *
 * <p>调用方必须在**每个任务开始时**调用本方法，而不是复用请求线程上的任何状态：
 * 撤销、范围收窄、票据到期都会在这一刻体现为拒绝（抛稳定错误），
 * 从而保证"后续受限步骤在撤销后停止执行"。
 */
public interface AiExecutionContextFactory {

    /**
     * 重建执行上下文。
     *
     * @param applicationId  应用编号
     * @param subjectType    主体类型
     * @param externalUserId 可信外部用户标识
     * @param resourceHints  本次任务需要的对象标识（用于裁剪范围）
     * @throws com.basicframework.framework.common.exception.ServiceException 主体不可用或范围解析拒绝时
     */
    AiExecutionContext rebuild(
            Long applicationId, AiSubjectType subjectType, String externalUserId, List<String> resourceHints);
}
