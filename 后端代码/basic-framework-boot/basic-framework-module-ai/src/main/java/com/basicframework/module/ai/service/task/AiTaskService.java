package com.basicframework.module.ai.service.task;

import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import java.util.List;

/**
 * 可恢复任务领取与租约（O03）。
 *
 * <p>约束：
 * <ul>
 *   <li><b>不重复领取</b>：领取是条件更新（CAS），同一时刻只有一个 worker 能把任务从 QUEUED 变成
 *       RUNNING 并拿到有效租约；</li>
 *   <li><b>租约栅栏</b>：续租与落库都必须带 (owner, epoch)，租约过期被接管后旧 worker 命中 0 行，
 *       不能续租也不能覆盖新结果；</li>
 *   <li><b>可恢复</b>：租约过期即回到待领取（未达重试上限）或置 FAILED（达到上限），
 *       进程中断不需要人工干预；</li>
 *   <li><b>身份重建</b>：worker 每次执行前用 {@link #rebuildIdentity} 从服务端事实重建身份与范围，
 *       不继承任何请求线程上的旧身份；撤销后重建直接失败。</li>
 * </ul>
 */
public interface AiTaskService {

    /** 领取一批任务（短事务；返回的任务由调用方在事务之外执行）。 */
    List<AiTaskLeaseDTO> claim(String workerId, int limit, int leaseSeconds);

    /** 续租；返回 false 表示租约已失效，worker 必须停止执行且不得写入结果。 */
    boolean heartbeat(AiTaskLeaseDTO lease, int leaseSeconds);

    /** 写入终态并释放租约；返回 false 表示栅栏未命中（结果已被新 worker 接管）。 */
    boolean finish(AiTaskLeaseDTO lease, String status, String errorCode);

    /** 恢复过期租约（恢复 Job 每分钟调用）：返回本次处理的任务数。 */
    int recoverExpiredLeases(int retryDelaySeconds, int limit);

    /** 当前持有有效租约的任务数（观测与测试用）。 */
    int countActiveLeases();

    /** 按运行重建执行身份与范围（撤销、范围收窄、应用停用都会在这里失败）。 */
    AiExecutionContext rebuildIdentity(Long runId);
}
