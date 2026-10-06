package com.basicframework.module.ai.service.query.crosssource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨源预算计量（Y04）：并发安全的行数/字节计量器，越界即**受控结束**。
 *
 * <p>这是"超过内存/行数预算受控结束"的实现本体。要点有三个：
 * <ol>
 *   <li><b>先记账再合并</b>：{@link #charge} 在把来源结果并入合计**之前**扣减预算，
 *       超限就抛 {@code AI_CROSS_SOURCE_RESULT_TOO_LARGE}，此时该来源的金额还没有进入合计。
 *       如果先合并再判断，超限的那一刻内存已经被吃掉了——那不是"受控结束"而是"先 OOM 再报错"；</li>
 *   <li><b>原子扣减</b>：多来源并发完成时用 CAS 循环扣减，避免两个来源同时看到"还剩 1MB"而双双放行；</li>
 *   <li><b>不放宽</b>：计量只增不减，重试同一个来源不会因为"上次扣过了"而被免费放行，
 *       否则重试就成了绕过预算的路径。</li>
 * </ol>
 *
 * <p>计量失败抛出后不做任何回滚：执行已终止，回滚会让"已经扣掉的预算"与"已经发生的内存占用"
 * 出现不一致，进而让下一次重试拿到一份被低估的预算。
 */
public final class CrossSourceBudgetAccountant {

    private final CrossSourceBudget budget;

    private final AtomicLong usedBytes = new AtomicLong();

    private final AtomicInteger usedRows = new AtomicInteger();

    private final AtomicInteger concurrentPeak = new AtomicInteger();

    private final AtomicInteger inFlight = new AtomicInteger();

    public CrossSourceBudgetAccountant(CrossSourceBudget budget) {
        this.budget = budget;
    }

    /**
     * 扣减一个来源的用量；越界即抛稳定错误码（受控结束，不截断）。
     *
     * @param sourceRows 该来源返回的行数
     * @param sourceBytes 该来源中间结果字节
     */
    public void charge(int sourceRows, long sourceBytes) {
        if (sourceRows < 0 || sourceBytes < 0) {
            throw AiCrossSourceExecutionErrors.resultTooLarge();
        }
        if (sourceRows > budget.maxSourceRows()) {
            // 单源行数越界：说明来源没有在自己的上限内结束，问题出在来源侧而不是全局预算
            throw AiCrossSourceExecutionErrors.resultTooLarge();
        }
        if (sourceBytes > budget.maxSourceBytes()) {
            throw AiCrossSourceExecutionErrors.resultTooLarge();
        }
        usedRows.addAndGet(sourceRows);
        reserveBytes(sourceBytes);
    }

    /** 原子扣减全局字节预算（CAS 循环：并发下不允许两个来源同时看到剩余额度）。 */
    private void reserveBytes(long sourceBytes) {
        while (true) {
            long current = usedBytes.get();
            long next = current + sourceBytes;
            if (next > budget.maxTotalBytes()) {
                throw AiCrossSourceExecutionErrors.resultTooLarge();
            }
            if (usedBytes.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /**
     * 进入一个来源：记录并发峰值，超出预算即拒绝。
     *
     * <p><b>这条拒绝在当前执行器下不会触发</b>：{@code AiCrossSourceQueryExecutor} 把线程池
     * 定成 {@code min(并发预算, 来源数)}，同时在跑的 {@code enter()} 天然不会超限。
     * 超出部分由那个无界队列排队等位——所以"拒绝"并不等于"不排队"，排队发生在池子里。
     *
     * <p>因此本方法当前是**不变量守卫**（有单测直接调用覆盖），而不是生产路径上的准入闸门。
     * 留着它是为了：执行器策略一旦改成"先起满线程再按预算准入"，这里就是真正的闸门，
     * 不必再去别处找并发上限从哪生效。
     */
    public void enter() {
        int current = inFlight.incrementAndGet();
        if (current > budget.maxConcurrentSources()) {
            inFlight.decrementAndGet();
            // 峰值只在**放行**时更新：把被拒的进入也算进峰值会让并发证据虚高，
            // 事后对账时"峰值 3、上限 2"看起来像计量坏了
            // 报并发超限而不是"行数/内存预算超限"：并发要去查来源扇出与调度，
            // 规模要去查单次取数形状，证据指向完全不同的东西
            throw AiCrossSourceExecutionErrors.concurrencyExceeded();
        }
        concurrentPeak.accumulateAndGet(current, Math::max);
    }

    /** 离开一个来源。 */
    public void exit() {
        inFlight.decrementAndGet();
    }

    /** 当前累计字节。 */
    public long usedBytes() {
        return usedBytes.get();
    }

    /** 当前累计行数。 */
    public int usedRows() {
        return usedRows.get();
    }

    /** 并发来源数峰值。 */
    public int concurrentPeak() {
        return concurrentPeak.get();
    }

    /** 导出为结果里的预算用量证据。 */
    public CrossSourceExecutionResult.BudgetUsage usage() {
        return new CrossSourceExecutionResult.BudgetUsage(usedBytes.get(), usedRows.get(), concurrentPeak.get());
    }
}
