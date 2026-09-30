package com.basicframework.framework.ai.core.realtime;

import java.util.Locale;
import java.util.Optional;

/**
 * 实时语音会话协议（X05 冻结，决策记录 ADR 0052）：平台对"实时通道"的稳定词汇。
 *
 * <p>取值语义（一经发布不得改语义）：
 * <ul>
 *   <li>{@link #WEBSOCKET}：信令与分帧音频都由应用层消息承载，平台/适配器负责分帧与回合序号；
 *       不需要 ICE/DTLS/SRTP，代理与端口面与既有部署一致；</li>
 *   <li>{@link #WEBRTC}：媒体面在浏览器与上游之间直连（SRTP），平台只做会话协商、事件同步与
 *       授权审计；打断依赖上游服务端 VAD，平台仍保留回合栅栏。</li>
 * </ul>
 *
 * <p><b>不假设供应商一致</b>：协议不是"供应商属性"，也不是"模型能力"——同一供应商的不同模型/端点
 * 可以只支持其中一种，甚至都不支持。平台只发布**已验证事实**：每个（端点, 配置版本, 协议）组合
 * 都要经适配器声明与真实探测确认（见 {@link RealtimeCapabilityReport}），未确认的组合不可受理；
 * 客户端显式请求协议，平台不做隐式降级或升格（ADR 0052 第 2 节）。
 */
public enum RealtimeProtocol {

    /** WebSocket：分帧音频走应用层消息（平台可逐帧拦截与计账）。 */
    WEBSOCKET(true),

    /** WebRTC：媒体走 SRTP 直连（平台不逐帧拦截，只保留回合栅栏与事件面）。 */
    WEBRTC(false);

    private final boolean framedAudioOverApplicationTransport;

    RealtimeProtocol(boolean framedAudioOverApplicationTransport) {
        this.framedAudioOverApplicationTransport = framedAudioOverApplicationTransport;
    }

    /**
     * 音频是否以**应用层分帧消息**承载。
     *
     * <p>为真时平台对音频逐帧有界计账（背压的唯一判据）；为假时平台只能对事件面计账，
     * 音频字节由媒体面自行处理——这一差异是会话受理时必须固定的会话事实。
     */
    public boolean framedAudioOverApplicationTransport() {
        return framedAudioOverApplicationTransport;
    }

    /** 按请求值解析协议（空白与大小写差异归一化）；未知取值返回空，调用方按不合规拒绝。 */
    public static Optional<RealtimeProtocol> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (RealtimeProtocol protocol : values()) {
            if (protocol.name().equals(normalized)) {
                return Optional.of(protocol);
            }
        }
        return Optional.empty();
    }
}
