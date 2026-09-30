/**
 * 实时语音会话契约（X05 冻结，决策记录 ADR 0052）。
 *
 * <p>本包只放**平台词汇与接缝**，不含任何厂商类型、网络实现或 Spring 依赖：
 * <ul>
 *   <li>协议与能力词汇：{@link com.basicframework.framework.ai.core.realtime.RealtimeProtocol}、
 *       {@link com.basicframework.framework.ai.core.realtime.RealtimeCapability}；</li>
 *   <li>会话事实：{@link com.basicframework.framework.ai.core.realtime.RealtimeSessionOpenRequest}、
 *       {@link com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat}；</li>
 *   <li>事件与结束原因：{@link com.basicframework.framework.ai.core.realtime.RealtimeEvent}、
 *       {@link com.basicframework.framework.ai.core.realtime.RealtimeCloseReason}；</li>
 *   <li>适配器接缝：{@link com.basicframework.framework.ai.core.realtime.RealtimeAdapter}、
 *       {@link com.basicframework.framework.ai.core.realtime.RealtimeCapabilityProbe}、
 *       {@link com.basicframework.framework.ai.core.realtime.RealtimeSessionChannel}；</li>
 *   <li>平台侧不变量模型：{@link com.basicframework.framework.ai.core.realtime.RealtimeTurnFence}、
 *       {@link com.basicframework.framework.ai.core.realtime.RealtimeBackpressure}。</li>
 * </ul>
 *
 * <p>边界（与 ADR 0052 一致）：
 * <ul>
 *   <li>能力不按供应商推断：每个（端点, 配置版本, 协议）都要声明 + 真实探测确认，未确认不可用；</li>
 *   <li>真实供应商实时链路在本环境**未验证**（无凭据与出网通道）：本包只冻结判据与契约，
 *       不提供任何"看起来能跑"的默认实现；</li>
 *   <li>厂商协议差异只允许出现在 {@code com.basicframework.framework.ai.provider} 或业务模块的
 *       适配器中，本包不引入厂商类型。</li>
 * </ul>
 */
package com.basicframework.framework.ai.core.realtime;
