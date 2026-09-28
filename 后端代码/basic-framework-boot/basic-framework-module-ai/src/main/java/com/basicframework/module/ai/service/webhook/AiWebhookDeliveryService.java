package com.basicframework.module.ai.service.webhook;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryAttemptDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookDeliveryLeaseDTO;
import java.time.Duration;
import java.util.List;

/**
 * Webhook 投递服务（X10）：入队、领取（租约）、落结论、死信重投与尝试留痕。
 *
 * <p>不变量：
 * <ol>
 *   <li><b>至少一次且至多一条</b>：同一「目标 × 事件 × 资源」最多一条投递行（唯一键兜底），
 *       投递本身至少执行一次（租约过期会自动回到队列），接收端用投递编号去重；</li>
 *   <li><b>投递与运行结果解耦</b>：投递只读运行事实；失败只写投递行与尝试行，
 *       绝不回写运行状态、绝不重跑运行、绝不重复产生业务副作用；</li>
 *   <li><b>有界重试</b>：只有可重试结论才退避重试，次数上限来自目标快照；超限置死信并记
 *       「重试预算耗尽」这个独立结论，底层原因保留在最近原因码里；</li>
 *   <li><b>每一步可核验</b>：投递行（状态 + 计数 + 原因码）与尝试行（每次完成的尝试的结论与耗时）
 *       构成完整事实链，日志与响应里没有目标密钥、投递正文与响应正文。</li>
 * </ol>
 */
public interface AiWebhookDeliveryService {

    /**
     * 入队：把「运行终态 × 启用目标 × 已订阅事件」补成投递行（幂等，缺一条补一条）。
     * 返回本次新建条数；已存在的投递行不会被重建（正文与编号因此保持稳定）。
     */
    int enqueueTerminalRuns(int limit);

    /** 领取一批投递（CAS + 租约；返回的租约由调用方在事务外执行投递）。 */
    List<AiWebhookDeliveryLeaseDTO> claim(String workerId, int limit, int leaseSeconds);

    /** 恢复过期租约（崩溃留下的 RUNNING：未达上限回队列、达上限置死信）；返回处理条数。 */
    int recoverExpiredLeases(int retryDelaySeconds, int limit);

    /** 按编号取投递行（仅供后台投递链路使用，不做主体范围判定）。 */
    AiWebhookDeliveryDO getForExecution(Long deliveryId);

    /**
     * 落结论：追加一条尝试行，并按结论迁移投递行状态（已送达 / 退避重试 / 失败终态）。
     *
     * @return true 表示投递行按本次结论更新生效；false 表示栅栏未命中（结果已被新 worker 接管）
     */
    boolean finish(AiWebhookDeliveryLeaseDTO lease, AiWebhookDeliveryOutcome outcome);

    /**
     * 人工重投（只对死信）：目标必须存在且启用；在**保留尝试序号**（单调递增）的前提下追加一份重试预算，
     * 并立即进入待领取队列。投递编号与正文不变，接收端的去重语义不受影响。
     */
    void redeliver(Long id);

    /** 读取投递（不存在即 404）。 */
    AiWebhookDeliveryDO get(Long id);

    /** 投递分页（死信查看：可按目标、状态、事件类型过滤）。 */
    PageResult<AiWebhookDeliveryDO> getPage(PageParam pageParam, Long targetId, String status, String eventType);

    /** 某次投递的尝试留痕（按尝试序号升序）。 */
    List<AiWebhookDeliveryAttemptDO> getAttempts(Long deliveryId);

    /** 按保留期分批清理尝试留痕（append-retention）；返回清理条数。 */
    int pruneAttempts(Duration retention, int limit);
}
