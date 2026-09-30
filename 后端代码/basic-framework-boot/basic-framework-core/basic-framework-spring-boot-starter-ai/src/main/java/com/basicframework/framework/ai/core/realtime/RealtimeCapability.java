package com.basicframework.framework.ai.core.realtime;

import java.util.Set;

/**
 * 实时语音能力（X05 冻结，ADR 0052）：平台在**每个（端点, 配置版本, 协议）**上分别验证的稳定词汇。
 *
 * <p>为什么不用 {@code ModelCapability} 的媒体能力推断：非实时 STT/TTS 是"一次请求-一次响应"，
 * 实时语音是"会话 + 双向流 + 打断"，两者在上游是完全不同的接口。声明一个 {@code SPEECH_TO_TEXT}
 * 既不能推出"能开实时会话"，也不能推出"支持服务端 VAD"。因此实时能力单独成表，逐项声明、
 * 逐项真实探测确认，缺一即不可用（不回退、不推断）。
 *
 * <p>各协议的必需能力集合由适配层给出（{@code RealtimeProtocolVerification}）：WebSocket 至少要有
 * {@link #SESSION_NEGOTIATION}/{@link #AUDIO_INPUT_STREAM}/{@link #AUDIO_OUTPUT_STREAM}；
 * WebRTC 因为在浏览器与上游之间直连媒体，额外要求 {@link #SERVER_VAD_INTERRUPTION}，
 * 否则平台无法在媒体面之外保证打断（会话受理时就拒绝，而不是"先跑起来再看"）。
 */
public enum RealtimeCapability {

    /** 会话协商：能建立一次实时会话并拿到会话级参数（有效期、音频参数、事件形态）。 */
    SESSION_NEGOTIATION,

    /** 上行音频流：能把平台侧的分帧音频送进会话（或协商出上行媒体通道）。 */
    AUDIO_INPUT_STREAM,

    /** 下行音频流：能收到会话输出音频（或协商出下行媒体通道）。 */
    AUDIO_OUTPUT_STREAM,

    /** 服务端 VAD / 打断：上游能在检测到用户说话时停止当前输出（仅作为**额外**保证，平台不依赖它）。 */
    SERVER_VAD_INTERRUPTION,

    /** 增量转写：会话内能返回逐段转写（含中间结果），供界面实时显示与审计留痕。 */
    INCREMENTAL_TRANSCRIPTION,

    /** 工具调用桥：会话内能返回工具调用请求（只作为数据；执行必须走平台的受控执行语义）。 */
    TOOL_CALL_BRIDGE;

    /** 会话可用的最小能力集合（任何协议都要有）：协商 + 双向音频。 */
    public static final Set<RealtimeCapability> BASELINE =
            Set.of(SESSION_NEGOTIATION, AUDIO_INPUT_STREAM, AUDIO_OUTPUT_STREAM);
}
