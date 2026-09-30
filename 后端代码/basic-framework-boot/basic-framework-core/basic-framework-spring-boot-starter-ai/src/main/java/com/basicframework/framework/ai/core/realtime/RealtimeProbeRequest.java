package com.basicframework.framework.ai.core.realtime;

import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;

/**
 * 实时能力探测请求（X05 冻结）：探测所需的全部输入，由平台从**已固定的端点快照**构造。
 *
 * <p>凭据只以 {@link ModelEndpointSnapshot} 的受控形式传入（该类型已冻结脱敏输出：不进入缓存键、
 * 不进入 toString、不进入日志）：探测与真实会话用同一份端点事实，避免"探测用的端点"和"会话用的
 * 端点"是两个不同配置。
 *
 * <p>探测必须使用平台内置合成音频夹具，不得使用真实用户数据。
 *
 * @param endpoint   端点快照（含端点编号、配置/凭据版本、地址、模型与解密后的凭据；仅受控内存可见）
 * @param protocol   被探测的协议
 * @param audioFormat 探测使用的音频格式（来自适配器支持集合，不得使用客户端输入）
 */
public record RealtimeProbeRequest(
        ModelEndpointSnapshot endpoint, RealtimeProtocol protocol, RealtimeAudioFormat audioFormat) {

    public RealtimeProbeRequest {
        if (endpoint == null) {
            throw new IllegalArgumentException("端点快照不能为空");
        }
        if (protocol == null) {
            throw new IllegalArgumentException("协议不能为空");
        }
        if (audioFormat == null) {
            throw new IllegalArgumentException("探测音频格式不能为空");
        }
    }
}
