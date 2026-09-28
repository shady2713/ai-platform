package com.basicframework.module.ai.service.tool.action;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.service.tool.AiToolDecision;
import com.basicframework.module.ai.service.tool.action.dto.AiToolActionCreateResult;
import java.util.Map;

/**
 * 工具动作与确认（D09）：参数冻结 + 到期挑战 + 确认状态机。
 *
 * <p>调用方（分析步骤调度）在政策判定为 CONFIRM 时创建动作；用户确认后由服务执行一次。
 */
public interface AiToolActionService {

    /**
     * 由 CONFIRM 判定创建动作（冻结参数、生成挑战与到期时间）。
     *
     * <p>写工具（X06）：动作登记业务幂等键；同一工具 + 同一业务键若已有"可能已生效"的动作
     * （PENDING/CONFIRMED/EXECUTING/UNKNOWN/EXECUTED），返回该动作并标记 {@code reused=true}
     * ——同一业务意图不会产生第二个动作，因此也不会有第二次副作用（AT-021）。
     */
    AiToolActionCreateResult createFromDecision(
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

    /**
     * 核对结果未定的动作（X06 显式路径，AT-019）。
     *
     * <p>只有 EXECUTING（执行中/进程崩溃）与 UNKNOWN（上游未确认）的动作可以核对：
     * <ul>
     *   <li>{@code mode=PROGRAM}：调用动作冻结的**登记核对查询**（按业务幂等键查），
     *       查到业务对象即 APPLIED，查不到即 NOT_APPLIED；查询本身失败则动作保持未定（502）；</li>
     *   <li>{@code mode=MANUAL}：操作员给出结论（APPLIED/NOT_APPLIED）与可选说明（≤200 字符，
     *       不含凭据与正文），用于无法程序核对或需要人工判断的场景。</li>
     * </ul>
     *
     * <p>核对是 CAS：并发核对只有一个赢家；核对成功后动作进入终态，绝不自动重放写请求
     * （未生效时由业务以**新的**确认重新发起）。
     */
    AiToolActionDO reconcile(
            Long actionId,
            Long applicationId,
            String subjectType,
            String externalUserId,
            String mode,
            String outcome,
            String note);

    /** 标记过期（终态）。 */
    AiToolActionDO expire(Long actionId);

    /** 查询动作（越权与不存在同语义）。 */
    AiToolActionDO getAction(Long actionId, Long applicationId, String subjectType, String externalUserId);

    /** 分页查询（按主体过滤）。 */
    PageResult<AiToolActionDO> getActionPage(
            Long applicationId, String subjectType, String externalUserId, Long runId, PageParam pageParam);
}
