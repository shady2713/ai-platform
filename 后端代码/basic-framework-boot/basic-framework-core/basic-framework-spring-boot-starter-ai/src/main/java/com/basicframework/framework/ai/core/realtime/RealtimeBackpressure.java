package com.basicframework.framework.ai.core.realtime;

/**
 * 有界输入缓冲（X05 冻结）：会话输入音频的计账模型，超限给出**显式结论**，不允许静默丢帧。
 *
 * <p>纯内存值对象（非线程安全）：平台侧跨实例的权威计账由会话行的原子更新承担（
 * {@code buffered_bytes + 帧字节 <= 上限} 的条件更新），本类是同一语义的可单测模型——
 * 用于请求处理的快速预判与响应里的占用/剩余字节，不代替持久层的条件更新。
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #offer(long)} 返回 {@link Outcome#ACCEPTED} 时占用增加，返回
 *       {@link Outcome#WOULD_OVERFLOW} 时占用不变（调用方必须按"结束会话"处理，而不是继续推流）；</li>
 *   <li>{@link #drain(long)} 释放已消费字节（例如适配器已取走的帧）；释放量超过占用时按 0 处理
 *       （不产生负占用，也不静默吞掉不一致）；</li>
 *   <li>零字节帧在构造与调用两个层面都被拒绝（空帧不是"合法的零开销"）。</li>
 * </ul>
 */
public final class RealtimeBackpressure {

    /** 计账结论。 */
    public enum Outcome {
        /** 已接受（占用增加）。 */
        ACCEPTED,
        /** 会超过上限：调用方必须按稳定原因结束会话，不得静默丢帧。 */
        WOULD_OVERFLOW
    }

    private final long capacityBytes;

    private long bufferedBytes;

    private RealtimeBackpressure(long capacityBytes, long bufferedBytes) {
        this.capacityBytes = capacityBytes;
        this.bufferedBytes = bufferedBytes;
    }

    /** 从零占用开始。 */
    public static RealtimeBackpressure of(long capacityBytes) {
        return of(capacityBytes, 0L);
    }

    /** 从已占用字节开始（用于把持久化事实装载为模型）。 */
    public static RealtimeBackpressure of(long capacityBytes, long bufferedBytes) {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("缓冲上限必须为正：" + capacityBytes);
        }
        if (bufferedBytes < 0 || bufferedBytes > capacityBytes) {
            throw new IllegalArgumentException("已占用字节越界：" + bufferedBytes + "/" + capacityBytes);
        }
        return new RealtimeBackpressure(capacityBytes, bufferedBytes);
    }

    /** 尝试接受字节：超限时不改变占用并返回 {@link Outcome#WOULD_OVERFLOW}。 */
    public Outcome offer(long byteCount) {
        requirePositive(byteCount);
        if (byteCount > remainingBytes()) {
            return Outcome.WOULD_OVERFLOW;
        }
        bufferedBytes += byteCount;
        return Outcome.ACCEPTED;
    }

    /** 释放已消费字节（释放量超过占用时归零）。 */
    public void drain(long byteCount) {
        requirePositive(byteCount);
        bufferedBytes = Math.max(0L, bufferedBytes - byteCount);
    }

    /** 当前占用字节。 */
    public long bufferedBytes() {
        return bufferedBytes;
    }

    /** 上限字节。 */
    public long capacityBytes() {
        return capacityBytes;
    }

    /** 剩余可接受字节。 */
    public long remainingBytes() {
        return capacityBytes - bufferedBytes;
    }

    private static void requirePositive(long byteCount) {
        if (byteCount <= 0) {
            throw new IllegalArgumentException("计账字节数必须为正：" + byteCount);
        }
    }
}
