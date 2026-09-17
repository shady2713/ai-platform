package com.basicframework.module.ai.enums;

/**
 * AI 任务状态：运行引擎内部的任务生命周期词汇（见 docs/ai-platform/12-critical-implementation-blueprints.md 第 2.2 节）。
 *
 * <p>只有 {@link #PENDING} 与 {@link #RETRY_WAIT} 可被 worker 领取；{@link #UNKNOWN}
 * 用于"外部写调用崩溃、结果不可证"的核对分支，不能自动重领执行。
 * 状态流转必须通过带版本/领取代次的 CAS 完成，禁止在 worker 本地计数。
 */
public enum AiTaskStatusEnum {

    /** 待领取。 */
    PENDING,

    /** 失败后等待重试窗口。 */
    RETRY_WAIT,

    /** 已被 worker 领取执行。 */
    RUNNING,

    /** 成功结束。 */
    SUCCEEDED,

    /** 失败结束。 */
    FAILED,

    /** 已取消。 */
    CANCELLED,

    /** 结果不可证（外部写调用崩溃），需人工或对账核对。 */
    UNKNOWN;

    /**
     * 是否可被 worker 领取。
     *
     * @return true 表示可领取
     */
    public boolean isClaimable() {
        return this == PENDING || this == RETRY_WAIT;
    }

    /**
     * 是否为终态。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
