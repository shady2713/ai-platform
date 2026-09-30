package com.basicframework.module.ai.service.realtime;

import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAcceptDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAudioPushDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeSessionViewDTO;

/**
 * 实时语音会话服务（X05，FR-37）：短寿命在线会话的受理、推流、打断、关麦、断线恢复与销毁。
 *
 * <p>不变量（全部由服务端判定，客户端只能请求）：
 * <ol>
 *   <li><b>受理即固定</b>：端点、配置版本、凭据版本、协议、音频格式、缓冲上限与绝对到期时间在受理时
 *       写入会话行；票据只存摘要、明文只出现一次。端点/协议必须通过"声明 + 真实探测确认"
 *       （ADR 0052），否则拒绝且不回退；</li>
 *   <li><b>背压有界</b>：上行音频按字节计账（有界），超限按 {@code audio-backpressure-exceeded}
 *       结束会话，**不静默丢帧**；关麦期间上行音频明确拒绝（{@code AI_REALTIME_MUTED_CONFLICT}）；</li>
 *   <li><b>打断即栅栏</b>：打断推进回合号；晚到的旧回合上行帧被拒绝（稳定码），晚到的旧回合
 *       适配器事件被丢弃并计数（{@code STALE_DROPPED} 留痕）；旧回合音频绝不继续输出；</li>
 *   <li><b>重连有界且幂等</b>：重连次数与时限都有上限；重连返回既有转写与工具状态，
 *       已终态的工具调用不会被第二次执行；</li>
 *   <li><b>到期与切换身份必关闭</b>：到期（{@code session-expired}）与"出示票据的身份与
 *       会话归属不一致"（{@code identity-switched}）都会关闭会话——身份不可跨会话复用；
 *       归属判定全部在服务端，越权与不存在同语义；</li>
 *   <li><b>并发受限</b>：同一主体/同一应用的有效会话数有上限，超限 429（不排队、不阻塞）。</li>
 * </ol>
 *
 * <p>本卡不在服务端执行任何真实供应商调用：受理会先经 {@link AiRealtimeEndpointVerifier} 验证，
 * 没有注册适配器的协议一律拒绝（本环境真实供应商链路**未验证**，见 ADR 0052）。
 */
public interface AiRealtimeSessionService {

    /** 受理会话（幂等：同键同形状复用；票据明文只在本响应或续票响应出现一次）。 */
    AiRealtimeSessionViewDTO accept(AiRealtimeAcceptDTO request);

    /** 查询会话（惰性物化到期/断线超时；越权与不存在同语义）。 */
    AiRealtimeSessionViewDTO getSession(Long sessionId);

    /** 上送一帧音频（回合栅栏 + 有界缓冲；超限结束会话并给出稳定码）。 */
    AiRealtimeSessionViewDTO pushAudio(Long sessionId, AiRealtimeAudioPushDTO frame);

    /** 打断（回合 +1）：旧回合仍在飞的事件随后被丢弃并计数。 */
    AiRealtimeSessionViewDTO interrupt(Long sessionId, Long turnNo);

    /** 关麦/开麦：关麦期间明确拒绝上行音频。 */
    AiRealtimeSessionViewDTO updateMuted(Long sessionId, boolean muted);

    /** 标记断线（媒体面断开）：开始有界重连窗口；重复调用不延长窗口。 */
    AiRealtimeSessionViewDTO detach(Long sessionId);

    /** 重连（必须出示票据）：有界次数与时限；返回转写与工具状态，不重复执行工具。 */
    AiRealtimeSessionViewDTO resume(Long sessionId, String ticket);

    /** 续票（必须出示当前票据，且当前票据未过期）：新票据替换旧票据，会话寿命不延长。 */
    AiRealtimeSessionViewDTO renewTicket(Long sessionId, String ticket);

    /** 关闭/销毁会话（幂等；已关闭直接返回既有终态）。 */
    AiRealtimeSessionViewDTO close(Long sessionId);

    /** 执行会话内工具调用（幂等：已终态返回既有结论，不重复执行）。 */
    AiRealtimeSessionViewDTO executeToolCall(Long sessionId, Long toolCallId);
}
