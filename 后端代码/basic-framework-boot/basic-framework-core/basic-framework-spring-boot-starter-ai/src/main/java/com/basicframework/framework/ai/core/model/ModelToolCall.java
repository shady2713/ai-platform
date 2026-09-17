package com.basicframework.framework.ai.core.model;

/**
 * 平台自有工具调用请求（M03）。
 *
 * <p>模型返回的 tool call 只作为**数据**暴露给平台执行策略：本接缝不注册任何工具回调，
 * 也不执行 Bean 方法或脚本。是否执行、如何授权由平台工具层决定（D08/D09）。
 *
 * @param id            厂商给出的调用标识（用于把结果回填到同一次对话）
 * @param name          工具名（平台侧白名单校验后才会真正执行）
 * @param argumentsJson 参数 JSON 文本（未执行，未做业务校验）
 */
public record ModelToolCall(String id, String name, String argumentsJson) {

    /** 参数缺失时用空对象代替，避免下游对 null 做分支。 */
    public ModelToolCall {
        argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }
}
