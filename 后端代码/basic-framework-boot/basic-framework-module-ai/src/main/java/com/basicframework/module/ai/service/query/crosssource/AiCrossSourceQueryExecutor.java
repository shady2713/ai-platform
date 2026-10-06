package com.basicframework.module.ai.service.query.crosssource;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionDO;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionSourceDO;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceExecutionMapper;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceSourceContributionMapper;
import com.basicframework.module.ai.service.queryplan.CrossSourceQueryPlan;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 有界跨源执行器（Y04）：**先源内聚合、再按版本化实体键关联、逐源计入、最后求和**。
 *
 * <p>执行顺序固定为"取数 → 预算扣减 → 计入台账 → 汇总"，每一步的失败都有明确出口：
 * <ol>
 *   <li><b>取数</b>：逐源按主键粒度预聚合后拉取有界中间结果。绝不把多源拼成一条多表 SQL——
 *       那样扇出即重复计算（Y03 §3.1）；</li>
 *   <li><b>预算扣减</b>：{@link CrossSourceBudgetAccountant#charge} 在并入合计**之前**扣减，
 *       越界抛 {@code AI_CROSS_SOURCE_RESULT_TOO_LARGE}。这是"受控结束"：金额还没进台账，
 *       内存也没被吃光，不存在"先 OOM 再报错"；</li>
 *   <li><b>计入台账</b>：每个来源在 {@code ai_cross_source_execution_source} 里最多一行
 *       （唯一键保证），重试走 CAS 覆盖而不是叠加——这是重试幂等的真实机制；</li>
 *   <li><b>汇总</b>：合计由"已计入的行"求和得到。求和口径与写入口径分离，
 *       因此即使同一来源被重取多次，合计也不会随重试次数膨胀。</li>
 * </ol>
 *
 * <p>一致性时间点取各来源 {@code as_of} 的**最小值**：跨源合计只能解释为
 * "所有来源都成立的那个时刻"。{@code maxSkewMillis} 把数据时间差放进结果里，
 * 超过容忍窗口则受控结束——不假装几个来源是同一时刻的。
 *
 * <p>部分失败策略来自 Y03 口径的 {@code optional}：必需来源失败整体受控结束；
 * 可选来源缺失写 MISSING 并把执行标记为 PARTIAL，绝不按 0 补齐。
 *
 * <p>任何受控结束都会把执行记录落到 FAILED 并带上稳定失败编号，因此"为什么没算出来"
 * 在执行记录里可查，而不是只消失在一次异常里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCrossSourceQueryExecutor {

    private final CrossSourceSourceFetcher sourceFetcher;

    private final AiCrossSourceExecutionMapper executionMapper;

    private final AiCrossSourceSourceContributionMapper contributionMapper;

    /**
     * 执行一次跨源聚合。
     *
     * @param plan         已通过 Y03 校验的跨源计划（扇出/币种/聚合顺序已判定）
     * @param requests     各来源的取数请求；必须与计划的来源一一对应
     * @param budget       执行预算（服务端硬顶，不可放大）
     * @param executionKey 幂等键（重试沿用同一键）
     */
    public CrossSourceExecutionResult execute(
            CrossSourceQueryPlan plan,
            List<CrossSourceSourceRequest> requests,
            CrossSourceBudget budget,
            String executionKey) {
        requireExecutable(plan, requests, executionKey);
        // 同一执行键 + 不同计划 = 冲突：否则同一个键先后跑出两个不同结果，调用方无法分辨
        AiCrossSourceExecutionDO existing = executionMapper.selectByExecutionKey(executionKey);
        if (existing != null && !existing.getPlanHash().equals(plan.planHash())) {
            throw AiCrossSourceExecutionErrors.executionKeyConflict();
        }
        // 已出**完整**结果的执行不允许原地重跑：SUCCEEDED 的合计是别人正在引用的数字，
        // REGISTERED 是待执行的容量登记，两者重算都必须换执行键。
        // PARTIAL 允许重跑——它的结果 `usable()` 为 false，本来就是"还没算完"的状态，
        // 补齐缺失来源后重跑是它的正常路径，去重交给台账而不是靠拒绝重试。
        if (existing != null && isFinalized(existing.getStatus())) {
            throw AiCrossSourceExecutionErrors.executionKeyConflict();
        }
        AiCrossSourceExecutionDO execution =
                existing != null ? existing : createExecution(plan, executionKey, requests);
        try {
            return runSources(execution, plan, requests, budget);
        } catch (ServiceException failure) {
            fail(execution, failure);
            throw failure;
        }
    }

    /**
     * 执行是否已产出**最终结果**（SUCCEEDED / REGISTERED）。
     *
     * <p>PARTIAL、FAILED 与 RUNNING 都**不**算：它们是"还没算完"或"正在重试"的状态，
     * 原地重跑是它们的正常路径。
     */
    private static boolean isFinalized(String status) {
        return AiCrossSourceExecutionDO.STATUS_SUCCEEDED.equals(status)
                || AiCrossSourceExecutionDO.STATUS_REGISTERED.equals(status);
    }

    /** 逐源取数、计入、汇总。 */
    private CrossSourceExecutionResult runSources(
            AiCrossSourceExecutionDO execution,
            CrossSourceQueryPlan plan,
            List<CrossSourceSourceRequest> requests,
            CrossSourceBudget budget) {
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(budget);
        // 缺失角色会被多个来源线程并发追加，必须用并发集合而不是 ArrayList
        Set<String> missingRoles = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(budget.maxConcurrentSources(), requests.size())), runnable -> {
                    Thread worker = new Thread(runnable, "cross-source-fetch");
                    worker.setDaemon(true);
                    return worker;
                });
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (CrossSourceSourceRequest request : requests) {
                pending.add(
                        pool.submit(() -> fetchAndCount(execution.getId(), request, budget, accountant, missingRoles)));
            }
            awaitAll(pending, budget);
        } finally {
            pool.shutdownNow();
        }
        return finalizeExecution(execution, plan, budget, accountant, List.copyOf(missingRoles));
    }

    /**
     * 等待全部来源完成，逐源受**自身**超时预算约束。
     *
     * <p>逐源 `get(timeout)` 而不是整体 `invokeAll`：卡片的第 2 步要求"每源超时"，
     * 整体等待会让一个慢来源把整个跨源执行拖到 N 倍超时。
     * 超时后 {@code cancel(true)} 中断该来源，其余来源继续，**先完成的来源不会因为
     * 别人超时而丢掉已计入的贡献**。
     *
     * <p>必需来源的失败会作为异常冒出来，由 {@code execute} 统一落 FAILED 终态；
     * 这里不吞异常——吞掉就等于把"某个必需来源没取到"变成"跨源执行成功但少一个数"。
     */
    private static void awaitAll(List<Future<?>> pending, CrossSourceBudget budget) {
        long deadlineNanos = System.nanoTime() + budget.sourceTimeout().toNanos() * pending.size();
        for (int index = 0; index < pending.size(); index++) {
            Future<?> future = pending.get(index);
            long remaining = deadlineNanos - System.nanoTime();
            try {
                // 每个来源分到一份超时预算；总上限是"单源超时 × 来源数"
                future.get(Math.max(1L, remaining), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                throw AiCrossSourceExecutionErrors.sourceTimeout();
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof ServiceException serviceException) {
                    throw serviceException;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw AiCrossSourceExecutionErrors.sourceFailed();
            } catch (InterruptedException interrupted) {
                // 中断发生在**调用线程**，语义是"调用方已放弃/容器关闭"，与来源无关。
                // 报 sourceTimeout() 有两个后果：retryable() 对它返回 true，调度器会去重试
                // 一个已被放弃的请求；且运维会去查来源耗时，而真正的原因是调用方走了。
                Thread.currentThread().interrupt();
                throw AiCrossSourceExecutionErrors.sourceCancelled();
            }
        }
    }

    /** 计划与请求必须能对上：计划里的每个来源角色都要有同版本的取数规格。 */
    private static void requireExecutable(
            CrossSourceQueryPlan plan, List<CrossSourceSourceRequest> requests, String executionKey) {
        if (plan == null || requests == null || requests.isEmpty() || executionKey == null || executionKey.isBlank()) {
            throw AiCrossSourceExecutionErrors.executionNotExists();
        }
        Map<String, Long> declared = new LinkedHashMap<>();
        for (CrossSourceQueryPlan.SourceSelection selection : plan.sources()) {
            declared.put(selection.role(), selection.mappingRevision());
        }
        for (CrossSourceSourceRequest request : requests) {
            Long revision = declared.get(request.role());
            // 不"就近取一个来源"：角色对不上说明计划与取数规格之间没有事实关联
            if (revision == null || !revision.equals(request.mappingRevision())) {
                throw AiCrossSourceExecutionErrors.planSourceNotDeclared();
            }
        }
    }

    private AiCrossSourceExecutionDO createExecution(
            CrossSourceQueryPlan plan, String executionKey, List<CrossSourceSourceRequest> requests) {
        AiCrossSourceExecutionDO execution = new AiCrossSourceExecutionDO()
                .setExecutionKey(executionKey)
                .setMetricCode(plan.metricCode())
                .setSemanticsRevision(plan.semanticsRevision())
                .setPlanHash(plan.planHash())
                .setMappingRevision(requests.get(0).mappingRevision())
                .setStatus(AiCrossSourceExecutionDO.STATUS_RUNNING)
                .setCurrency(plan.currency())
                .setMaxSkewMillis(0L)
                .setTotalBytes(0L)
                .setTotalRows(0)
                .setConcurrentPeak(0)
                .setVersion(0);
        executionMapper.insert(execution);
        return execution;
    }

    /**
     * 取一个来源并计入台账。
     *
     * <p>顺序是承重的：先取数、再扣预算、最后才写入金额。预算越界时金额还没进台账，
     * 因此"受控结束"之后不存在一个被部分写入的合计。
     */
    private void fetchAndCount(
            Long executionId,
            CrossSourceSourceRequest request,
            CrossSourceBudget budget,
            CrossSourceBudgetAccountant accountant,
            Set<String> missingRoles) {
        accountant.enter();
        try {
            CrossSourceSourceFetcher.FetchedRows fetched = sourceFetcher.fetch(request, budget);
            if (fetched.truncated()) {
                // 源内已预聚合的 SUM 被截断后仍是"合法数字"但偏小：宁可阻断也不给偏小的合计
                throw AiCrossSourceExecutionErrors.resultTruncated();
            }
            if (fetched.asOf() == null) {
                // 没有数据时间点就无法解释这个合计属于哪个时刻
                throw AiCrossSourceExecutionErrors.consistencySkew();
            }
            BigDecimal amount = sumByEntityKey(request, fetched);
            accountant.charge(fetched.rows().size(), fetched.byteSize());
            count(executionId, request, amount, fetched);
        } catch (ServiceException failure) {
            handleSourceFailure(executionId, request, failure, missingRoles);
        } finally {
            accountant.exit();
        }
    }

    /**
     * 按版本化实体键求和。
     *
     * <p>先校验所有键同版本再求和：跨版本合并会把两份事实并成一份，
     * 这种错误在报表上完全看不出来。
     */
    private static BigDecimal sumByEntityKey(
            CrossSourceSourceRequest request, CrossSourceSourceFetcher.FetchedRows fetched) {
        List<CrossSourceEntityKey> keys = new ArrayList<>(request.entityKeys());
        fetched.rows().forEach(row -> keys.add(row.entityKey()));
        CrossSourceEntityKey.requireSingleMappingRevision(keys);
        BigDecimal total = BigDecimal.ZERO;
        for (CrossSourceSourceFetcher.PreAggregatedRow row : fetched.rows()) {
            total = total.add(row.amount() == null ? BigDecimal.ZERO : row.amount());
        }
        return total;
    }

    /**
     * 计入一个来源（幂等写）。
     *
     * <p>首次走 insert（唯一键保证每来源一行）；已存在则走 CAS **覆盖**。
     * 覆盖而不是累加，是"重试不重复汇总"的实现点——金额字段被赋新值，
     * 无论重试多少次，库里始终只有一份。
     */
    private void count(
            Long executionId,
            CrossSourceSourceRequest request,
            BigDecimal amount,
            CrossSourceSourceFetcher.FetchedRows fetched) {
        AiCrossSourceExecutionSourceDO row = contributionMapper.selectByRole(executionId, request.role());
        if (row == null) {
            contributionMapper.insert(newContribution(executionId, request, amount, fetched));
            return;
        }
        int updated = contributionMapper.overwriteCountedWithVersion(
                executionId,
                request.role(),
                amount,
                fetched.rows().size(),
                fetched.byteSize(),
                fetched.asOf(),
                fetched.elapsedMillis(),
                row.getVersion());
        if (updated == 0) {
            // 并发重试：另一个执行者已改过这一行。重读确认它仍是"已计入"即视为同一份贡献，
            // 绝不在此再叠一份——这正是"重试幂等"与"重试时小心一点"的区别
            AiCrossSourceExecutionSourceDO latest = contributionMapper.selectByRole(executionId, request.role());
            if (latest == null || !AiCrossSourceExecutionSourceDO.STATUS_COUNTED.equals(latest.getStatus())) {
                throw AiCrossSourceExecutionErrors.alreadyCounted();
            }
        }
    }

    private static AiCrossSourceExecutionSourceDO newContribution(
            Long executionId,
            CrossSourceSourceRequest request,
            BigDecimal amount,
            CrossSourceSourceFetcher.FetchedRows fetched) {
        return new AiCrossSourceExecutionSourceDO()
                .setExecutionId(executionId)
                .setRole(request.role())
                .setDatasetCode(request.datasetCode())
                .setDatasetVersion(request.datasetVersion())
                .setMappingRevision(request.mappingRevision())
                .setStatus(AiCrossSourceExecutionSourceDO.STATUS_COUNTED)
                .setAmount(amount)
                .setRowCount(fetched.rows().size())
                .setByteSize(fetched.byteSize())
                .setSourceAsOf(fetched.asOf())
                .setElapsedMillis(fetched.elapsedMillis())
                .setAttemptCount(1)
                .setVersion(0);
    }

    /**
     * 单源失败的处置：必需来源整体受控结束，可选来源记为缺失。
     *
     * <p>必需来源失败时**重抛原始异常**而不是包一层 {@code SOURCE_FAILED_CONFLICT}：
     * 上游给出的往往是 {@code AI_CONNECTOR_QUERY_FAILED} 这类**可操作**的编号
     * （对象未授权、连接不可达），包一层会把"该找谁、该改什么"的信息抹掉。
     * 跨源侧的信息不丢——它写进台账（该来源行标 {@code FAILED}）与执行记录的失败编号。
     *
     * <p>可选来源的失败不允许"按 0 补齐"——{@code MISSING} 与"金额确实是 0"
     * 在报表上是两件不同的事，因此缺失角色会进结果与执行记录。
     */
    private void handleSourceFailure(
            Long executionId, CrossSourceSourceRequest request, ServiceException failure, Set<String> missingRoles) {
        boolean required = !request.optional();
        if (required) {
            log.warn("跨源必需来源失败：role={}, code={}", request.role(), failure.getCode());
        } else {
            missingRoles.add(request.role());
        }
        AiCrossSourceExecutionSourceDO row = contributionMapper.selectByRole(executionId, request.role());
        String status =
                required ? AiCrossSourceExecutionSourceDO.STATUS_FAILED : AiCrossSourceExecutionSourceDO.STATUS_MISSING;
        if (row == null) {
            contributionMapper.insert(new AiCrossSourceExecutionSourceDO()
                    .setExecutionId(executionId)
                    .setRole(request.role())
                    .setDatasetCode(request.datasetCode())
                    .setDatasetVersion(request.datasetVersion())
                    .setMappingRevision(request.mappingRevision())
                    .setStatus(status)
                    .setAmount(BigDecimal.ZERO)
                    .setRowCount(0)
                    .setByteSize(0L)
                    .setElapsedMillis(0L)
                    .setAttemptCount(1)
                    .setVersion(0));
        } else {
            contributionMapper.markStatusWithVersion(executionId, request.role(), status, row.getVersion());
        }
        if (required) {
            throw failure;
        }
    }

    /**
     * 收尾：按"已计入的行"求和，判定一致性与完整度，并做终态 CAS。
     *
     * <p>合计只从台账求和，绝不用内存里的累加值——这是重试不重复汇总的最后一道保证：
     * {@code status='COUNTED'} 的行每个来源只有一份，合计因此与重试次数无关。
     */
    private CrossSourceExecutionResult finalizeExecution(
            AiCrossSourceExecutionDO execution,
            CrossSourceQueryPlan plan,
            CrossSourceBudget budget,
            CrossSourceBudgetAccountant accountant,
            List<String> missingRoles) {
        List<AiCrossSourceExecutionSourceDO> counted = contributionMapper.selectCountedSources(execution.getId());
        BigDecimal total = BigDecimal.ZERO;
        LocalDateTime earliest = null;
        LocalDateTime latest = null;
        for (AiCrossSourceExecutionSourceDO row : counted) {
            total = total.add(row.getAmount() == null ? BigDecimal.ZERO : row.getAmount());
            if (row.getSourceAsOf() != null) {
                earliest = earliest == null || row.getSourceAsOf().isBefore(earliest) ? row.getSourceAsOf() : earliest;
                latest = latest == null || row.getSourceAsOf().isAfter(latest) ? row.getSourceAsOf() : latest;
            }
        }
        long skewMillis = (earliest == null || latest == null)
                ? 0L
                : Duration.between(earliest, latest).toMillis();
        if (skewMillis > budget.maxSkewSeconds() * 1000L) {
            // 跨源合计只能解释为"所有来源都成立的那个时刻"；偏移过大时这个时刻离谁都太远
            throw AiCrossSourceExecutionErrors.consistencySkew();
        }
        boolean complete = missingRoles.isEmpty();
        String status = complete ? AiCrossSourceExecutionDO.STATUS_SUCCEEDED : AiCrossSourceExecutionDO.STATUS_PARTIAL;
        int updated = executionMapper.updateStatusWithVersion(
                execution.getId(),
                // CAS 的 fromStatus 用**实际读到的**状态而不是写死 RUNNING：
                // FAILED 执行的合法重跑同样要能收尾，否则重试永远停在 FAILED
                execution.getStatus(),
                status,
                total,
                plan.currency(),
                earliest,
                skewMillis,
                CrossSourceRoles.encode(missingRoles),
                accountant.usedBytes(),
                accountant.usedRows(),
                accountant.concurrentPeak(),
                null,
                execution.getVersion());
        if (updated == 0) {
            // 终态 CAS 未命中：并发收尾只有一个赢家，另一个按冲突处理而不是覆盖
            throw AiCrossSourceExecutionErrors.executionKeyConflict();
        }
        return new CrossSourceExecutionResult(
                execution.getExecutionKey(),
                plan.planHash(),
                plan.metricCode(),
                plan.semanticsRevision(),
                plan.currency(),
                total,
                counted.stream().map(AiCrossSourceQueryExecutor::toSourceResult).toList(),
                earliest,
                skewMillis,
                missingRoles,
                accountant.usage(),
                complete);
    }

    private static CrossSourceExecutionResult.SourceResult toSourceResult(AiCrossSourceExecutionSourceDO row) {
        return new CrossSourceExecutionResult.SourceResult(
                row.getRole(),
                row.getDatasetCode(),
                row.getDatasetVersion(),
                row.getMappingRevision(),
                row.getAmount(),
                row.getRowCount() == null ? 0 : row.getRowCount(),
                row.getByteSize() == null ? 0L : row.getByteSize(),
                row.getSourceAsOf(),
                row.getElapsedMillis() == null ? 0L : row.getElapsedMillis(),
                true);
    }

    /** 受控结束：落 FAILED 终态并带上稳定失败编号，让"为什么没算出来"在执行记录里可查。 */
    private void fail(AiCrossSourceExecutionDO execution, ServiceException failure) {
        executionMapper.updateStatusWithVersion(
                execution.getId(),
                execution.getStatus(),
                AiCrossSourceExecutionDO.STATUS_FAILED,
                null,
                // 币种与偏移列是 NOT NULL：失败路径也必须回填，否则受控结束本身会在库上报错，
                // 把"稳定失败编号"换成一条看不出所以然的约束冲突
                execution.getCurrency() == null ? "NONE" : execution.getCurrency(),
                null,
                0L,
                CrossSourceRoles.encode(List.of()),
                0L,
                0,
                0,
                failure.getCode(),
                execution.getVersion());
        log.warn("跨源执行受控结束：executionKey={}, code={}", execution.getExecutionKey(), failure.getCode());
    }
}
