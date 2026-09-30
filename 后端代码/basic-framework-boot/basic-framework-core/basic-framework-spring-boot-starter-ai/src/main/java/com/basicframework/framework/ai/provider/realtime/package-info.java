/**
 * 实时语音厂商适配层（X05）：协议验证判据与（将来的）厂商适配器实现。
 *
 * <p>本包现只有 {@link com.basicframework.framework.ai.provider.realtime.RealtimeProtocolVerification}
 * ——平台侧的验证判据（必需能力、协议一致性、音频格式范围）。**没有**真实厂商的实时适配器实现：
 * 本环境没有实时语音供应商的凭据与出网通道，任何"看起来能跑"的实现都只能是假数据，因此交付物
 * 明确不含它（ADR 0052 的"未验证项"）。生产接入时，真实适配器实现放在本包（或业务模块的适配层），
 * 并必须提供真实探测证据。
 *
 * <p>厂商差异（信令形状、分帧方式、VAD 事件名、错误码）只允许出现在适配器实现里；
 * 平台只消费 {@code com.basicframework.framework.ai.core.realtime} 的冻结契约。
 */
package com.basicframework.framework.ai.provider.realtime;
