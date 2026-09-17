package com.basicframework.framework.ai.core.model;

import java.time.Duration;

/**
 * 自有模型请求（M02 冻结 v1）。
 *
 * <p>只包含平台语义：调用方不接触任何厂商类型；超时与取消语义在实现层落实。
 */
public record ModelRequest(String modelId, String prompt, Duration timeout) {

    /** 构造带默认超时的请求。 */
    public static ModelRequest of(String modelId, String prompt) {
        return new ModelRequest(modelId, prompt, null);
    }
}
