package com.basicframework.module.ai.service.usage;

/**
 * 计量记录出口（M05）：Q02 用它把调用计量持久化到账本表。
 *
 * <p>在持久化交付前允许没有实现：调用链路通过 {@code ObjectProvider} 解析，缺失时记录仍会
 * 返回给调用方，但不落库。实现必须幂等（同一 {@code invocationId} 重复投递不得重复计费）。
 */
public interface AiModelUsageRecorder {

    /** 记录一次调用计量；实现不得因此抛断业务调用链（失败由实现自行处理与告警）。 */
    void record(AiModelInvocationRecord record);
}
