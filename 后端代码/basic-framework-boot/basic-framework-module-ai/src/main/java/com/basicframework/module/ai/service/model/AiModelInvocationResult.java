package com.basicframework.module.ai.service.model;

import com.basicframework.module.ai.service.usage.AiModelInvocationRecord;

/**
 * 模型调用结果（M05）：自有输出 + 本次调用的计量记录。
 *
 * <p>调用方拿到输出即可，账本记录由 Q02 的 {@code AiModelUsageRecorder} 持久化；
 * 结果里再带一份便于上层在事务边界内自行处理（例如与业务写入同事务入账）。
 *
 * @param record 计量记录（含 invocationId、耗时、用量语义与失败原因）
 * @param output 自有输出（模型响应/结构化结果/嵌入响应）
 * @param <T>    输出类型
 */
public record AiModelInvocationResult<T>(AiModelInvocationRecord record, T output) {}
