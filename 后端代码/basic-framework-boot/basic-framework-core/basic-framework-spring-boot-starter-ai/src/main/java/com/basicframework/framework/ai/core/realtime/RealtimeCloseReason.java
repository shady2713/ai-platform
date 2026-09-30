package com.basicframework.framework.ai.core.realtime;

import java.util.Locale;
import java.util.Optional;

/**
 * 会话结束原因（X05 冻结）：稳定码是长期协议，一经发布不得改语义。
 *
 * <p>结束原因区分三类，语义不同：
 * <ul>
 *   <li><b>正常结束</b>：客户端关闭、入口静音后关闭、上游正常结束；</li>
 *   <li><b>边界触发</b>：到期、身份切换、背压超限、重连预算/时限耗尽——这些都由平台不变量触发，
 *       必须关闭会话而不是继续占用上游通道与平台资源；</li>
 *   <li><b>失败</b>：通道打开失败、上游失败——带稳定明细码，不携带上游报文。</li>
 * </ul>
 */
public enum RealtimeCloseReason {

    /** 客户端显式关闭（幂等：已关闭会话再次关闭不产生新副作用）。 */
    CLIENT_CLOSED("client-closed"),

    /** 会话绝对到期（读取/使用时惰性物化，不依赖常驻扫描任务）。 */
    SESSION_EXPIRED("session-expired"),

    /** 会话被另一个主体访问：身份不可跨会话复用。 */
    IDENTITY_SWITCHED("identity-switched"),

    /** 输入音频超过会话有界缓冲上限：按稳定原因结束，绝不静默丢帧。 */
    AUDIO_BACKPRESSURE_EXCEEDED("audio-backpressure-exceeded"),

    /** 重连次数耗尽。 */
    REATTACH_BUDGET_EXCEEDED("reattach-budget-exceeded"),

    /** 重连时限耗尽（断线后太久没有回来）。 */
    REATTACH_TIMEOUT("reattach-timeout"),

    /** 通道打开失败（适配器无法建立上游会话）。 */
    OPEN_FAILED("open-failed"),

    /** 上游正常结束会话。 */
    ADAPTER_ENDED("adapter-ended"),

    /** 上游/适配器失败结束会话（明细码给稳定原因）。 */
    ADAPTER_FAILED("adapter-failed");

    private final String code;

    RealtimeCloseReason(String code) {
        this.code = code;
    }

    /** 稳定结束码（持久化与协议里出现的唯一形状）。 */
    public String code() {
        return code;
    }

    /** 是否由客户端发起（客户端发起的原因在协议里可直接回显）。 */
    public boolean clientInitiated() {
        return this == CLIENT_CLOSED;
    }

    /** 按稳定码解析；未知码返回空（调用方按"会话事实损坏"处理，不猜原因）。 */
    public static Optional<RealtimeCloseReason> parse(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (RealtimeCloseReason reason : values()) {
            if (reason.code.equals(normalized)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
