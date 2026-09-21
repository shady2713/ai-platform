package com.basicframework.module.ai.service.tool.action;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import java.util.Map;

/**
 * 工具动作与确认（D09）：参数冻结 + 到期挑战 + 确认状态机。
 *
 * <p>调用方（分析步骤调度）在政策判定为 CONFIRM 时创建动作；用户确认后由服务执行一次。
 */
public interface AiToolActionService {

    /** 由 CONFIRM 判定创建动作（冻结参数、生成挑战与到期时间）。 */
    AiToolActionDO createFromDecision(
            AiToolDecision decision, Long runId, Long applicationId, String subjectType, String externalUserId);

    /** 确认（同一主体 + 同一挑战 + 同一参数哈希 + 未过期 + 当前政策仍为 CONFIRM）。 */
    AiToolActionDO confirm(
            Long actionId,
            Long applicationId,
            String subjectType,
            String externalUserId,
            String challenge,
            Map<String, Object> arguments);

    /** 拒绝（终态，不执行）。 */
    AiToolActionDO reject(
            Long actionId, Long applicationId, String subjectType, String externalUserId, String challenge);

    /** 执行（只有 CONFIRMED 能执行一次；重复/并发执行被 CAS 挡住）。 */
    AiToolActionDO execute(Long actionId, Long applicationId, String subjectType, String externalUserId);

    /** 标记过期（终态）。 */
    AiToolActionDO expire(Long actionId);

    /** 查询动作（越权与不存在同语义）。 */
    AiToolActionDO getAction(Long actionId, Long applicationId, String subjectType, String externalUserId);

    /** 分页查询（按主体过滤）。 */
    PageResult<AiToolActionDO> getActionPage(
            Long applicationId, String subjectType, String externalUserId, Long runId, PageParam pageParam);
}
