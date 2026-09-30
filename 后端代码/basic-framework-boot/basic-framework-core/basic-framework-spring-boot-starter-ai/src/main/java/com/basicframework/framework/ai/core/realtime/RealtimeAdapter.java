package com.basicframework.framework.ai.core.realtime;

import java.util.Set;

/**
 * 实时适配器（X05 冻结，ADR 0052）：厂商协议差异的**唯一**隔离层。
 *
 * <p>一个适配器实例只服务**一个**协议（{@link #protocol()}）：WebSocket 与 WebRTC 的媒体面、
 * 失败形态与部署要求完全不同，把它们塞进同一个适配器会强迫平台假设"两种协议形状一致"。
 * 同一协议的不同厂商实现可以有多个适配器，但一个端点在一次会话里只对应一个已注册适配器，
 * 平台不做候选遍历、不做跨供应商回退。
 *
 * <p>生命周期：适配器由平台装配（Spring Bean）；未注册适配器的协议对应"平台不知道的能力"，
 * 会话受理直接拒绝（稳定码），而不是"先跑起来再看"。
 */
public interface RealtimeAdapter extends RealtimeCapabilityProbe {

    /** 适配器实现的协议（1:1）。 */
    RealtimeProtocol protocol();

    /** 该适配器支持的音频格式集合（会话受理时按规范形式命中；不在集合内即拒绝）。 */
    Set<RealtimeAudioFormat> supportedAudioFormats();

    /**
     * 打开一次会话通道（真实会话协商）。
     *
     * <p>失败形态：预期内的失败抛 {@code ModelException}（平台按稳定原因结束/拒绝会话），
     * 不返回"半开"的通道；实现不得缓存跨会话的状态。
     */
    RealtimeSessionChannel open(RealtimeSessionOpenRequest request);
}
