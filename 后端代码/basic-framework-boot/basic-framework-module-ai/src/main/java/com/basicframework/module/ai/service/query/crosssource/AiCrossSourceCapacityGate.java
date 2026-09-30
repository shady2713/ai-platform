package com.basicframework.module.ai.service.query.crosssource;

import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionDO;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceExecutionMapper;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceSourceContributionMapper;
import com.basicframework.module.ai.service.queryplan.CrossSourceQueryPlan;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 跨源容量准入（Y04 第三步）：**超容量的数仓接口拒绝或转登记**。
 *
 * <p>容量按"预估扇出行数"判定：{@code 来源数 × 每源行数预算}。这是能在执行前算出来的
 * 唯一诚实的容量指标——真实基数要等查询跑完才知道，那时钱已经花了。
 *
 * <p>两条出口，**不引入分布式查询集群**：
 * <ol>
 *   <li><b>拒绝</b>：预估超过容量上限即抛 {@code AI_CROSS_SOURCE_CAPACITY_EXCEEDED}。
 *       让调用方缩小范围或改口径，而不是先跑起来再说；</li>
 *   <li><b>转登记</b>：把执行落成 {@code STATUS_REGISTERED} 并返回执行记录，
 *       等待人工或批量窗口处理。登记是**可查的**（执行记录在库里有行），
 *       而不是丢进一个内存队列——队列会在重启时丢掉这些请求。</li>
 * </ol>
 *
 * <p>为什么明确不引入分布式查询集群：那是另一个数量级的架构承诺（跨节点调度、
 * 分布式一致性与新的运维面），不该由一个"有界执行"卡片顺手引入。
 * 本卡的选择是把"要不要为此付这个代价"显式交回给决策者。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCrossSourceCapacityGate {

    /** 数仓容量上限（来源数）：超过即拒绝或转登记。 */
    public static final int DEFAULT_WAREHOUSE_SOURCE_CAPACITY = 8;

    private final AiCrossSourceExecutionMapper executionMapper;

    private final AiCrossSourceSourceContributionMapper contributionMapper;

    /**
     * 容量准入判定。
     *
     * @param plan     已校验的跨源计划
     * @param budget   执行预算（每源行数预算是容量估算的分母）
     * @param capacity 数仓来源数上限；小于 1 时用 {@link #DEFAULT_WAREHOUSE_SOURCE_CAPACITY}
     * @param allowRegistration 是否允许转登记（false 时超容量一律拒绝）
     * @param executionKey 幂等键（转登记时作为执行记录的键）
     * @return 放行返回 {@code null}；转登记返回已落库的 REGISTERED 执行记录
     * @throws RuntimeException 超容量且不允许转登记时抛 {@code AI_CROSS_SOURCE_CAPACITY_EXCEEDED}
     */
    public AiCrossSourceExecutionDO admit(
            CrossSourceQueryPlan plan,
            CrossSourceBudget budget,
            int capacity,
            boolean allowRegistration,
            String executionKey) {
        if (plan == null || budget == null) {
            throw AiCrossSourceExecutionErrors.planSourceNotDeclared();
        }
        int limit = capacity < 1 ? DEFAULT_WAREHOUSE_SOURCE_CAPACITY : capacity;
        int sourceCount = plan.sources().size();
        if (sourceCount <= limit) {
            return null;
        }
        long estimatedRows = (long) sourceCount * budget.maxSourceRows();
        if (!allowRegistration) {
            log.warn("跨源执行超容量被拒：sources={}, capacity={}, estimatedRows={}", sourceCount, limit, estimatedRows);
            throw AiCrossSourceExecutionErrors.capacityExceeded();
        }
        return register(plan, sourceCount, limit, estimatedRows, executionKey);
    }

    /** 转登记：落一条 REGISTERED 执行记录（可查、可复核，不丢在内存队列里）。 */
    private AiCrossSourceExecutionDO register(
            CrossSourceQueryPlan plan, int sourceCount, int limit, long estimatedRows, String executionKey) {
        String key = executionKey == null || executionKey.isBlank()
                ? "crosssource-register-" + plan.planHash()
                : executionKey;
        AiCrossSourceExecutionDO existing = executionMapper.selectByExecutionKey(key);
        if (existing != null) {
            // 已登记过：返回同一条而不是再登记一次，避免重复登记把请求数放大
            if (!AiCrossSourceExecutionDO.STATUS_REGISTERED.equals(existing.getStatus())) {
                throw AiCrossSourceExecutionErrors.capacityRegistrationConflict();
            }
            return existing;
        }
        AiCrossSourceExecutionDO registration = new AiCrossSourceExecutionDO()
                .setExecutionKey(key)
                .setMetricCode(plan.metricCode())
                .setSemanticsRevision(plan.semanticsRevision())
                .setPlanHash(plan.planHash())
                .setMappingRevision(0L)
                .setStatus(AiCrossSourceExecutionDO.STATUS_REGISTERED)
                .setCurrency(plan.currency())
                .setMaxSkewMillis(0L)
                .setMissingRoles(CrossSourceRoles.encode(List.of()))
                .setTotalBytes(estimatedRows)
                .setTotalRows(sourceCount)
                .setConcurrentPeak(limit)
                .setVersion(0);
        executionMapper.insert(registration);
        log.info("跨源执行超容量转登记：key={}, sources={}, limit={}, estimatedRows={}", key, sourceCount, limit, estimatedRows);
        return registration;
    }

    /** 登记记录是否可转执行：只有 REGISTERED 状态可继续，其它终态一律阻断。 */
    public boolean executable(AiCrossSourceExecutionDO registration) {
        if (registration == null) {
            return false;
        }
        if (!AiCrossSourceExecutionDO.STATUS_REGISTERED.equals(registration.getStatus())) {
            throw AiCrossSourceExecutionErrors.capacityRegistrationConflict();
        }
        return contributionMapper.selectByRole(registration.getId(), "order") == null;
    }
}
