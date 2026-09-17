package com.basicframework.module.ai.api.run;

/**
 * AI 运行状态：与开放 API 契约 {@code RunStatus} 一一对应
 * （权威定义见 docs/ai-platform/contracts/openapi-core.json 的 {@code RunStatus} schema）。
 *
 * <p>状态词汇由协议冻结，模块内部持久化与对外事件都必须使用同一词汇，不能各自定义平行状态。
 * 终态判定属于平台语义：{@link #SUCCEEDED}、{@link #FAILED}、{@link #CANCELLED} 之后不再流转。
 */
public enum AiRunStatusEnum {

    /** 已受理，等待执行。 */
    QUEUED,

    /** 执行中。 */
    RUNNING,

    /** 等待用户补充输入。 */
    WAITING_INPUT,

    /** 等待用户确认（工具或写入类动作）。 */
    WAITING_CONFIRMATION,

    /** 成功结束。 */
    SUCCEEDED,

    /** 失败结束。 */
    FAILED,

    /** 已取消。 */
    CANCELLED;

    /**
     * 是否为终态：终态运行不再产生新的输出，也不能被后续事件覆盖。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
