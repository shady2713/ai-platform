package com.basicframework.framework.ai.core.realtime;

import java.util.List;

/**
 * 已打开的实时会话通道（X05 冻结）：平台与适配器之间的最小往返面。
 *
 * <p>为什么是"推音频 + 拉事件"而不是流式回调：平台侧的状态（回合栅栏、有界缓冲、工具去重）
 * 必须以**单调、可持久化**的顺序消费事件；回调会把并发与顺序责任推给适配器实现。
 * {@link #pollEvents()} 是**非阻塞**的：没有事件返回空列表，由调用方决定下一次轮询时机。
 *
 * <p>实现约束：
 * <ul>
 *   <li>{@link #pushAudio} 只在通道仍打开时接受；通道已关闭时抛 {@code ModelException}
 *       （平台按稳定原因结束会话），绝不静默吞帧；返回值必须如实回答"上游会话是否已消费该帧"；
 *       返回 {@code false} 表示帧仍在适配器队列里，平台按有界缓冲继续计账（这是背压的唯一来源，
 *       谎报 {@code true} 等于把有界缓冲变成无界）；</li>
 *   <li>{@link #interrupt} 是"停止当前输出"的明确指令：实现必须让后续 {@link #pollEvents()}
 *       不再返回被打断回合的音频事件（晚到的旧回合事件由平台栅栏二次兜底丢弃）；</li>
 *   <li>{@link #close} 幂等：重复关闭不产生新副作用，且必须在有限时间内返回。</li>
 * </ul>
 */
public interface RealtimeSessionChannel {

    /**
     * 送入一帧上行音频（背压计账已在平台侧完成；本方法只负责送达）。
     *
     * @return true=上游会话已消费该帧（平台释放缓冲区占用）；false=帧仍在适配器队列中（占用保留）
     */
    boolean pushAudio(RealtimeAudioFrame frame);

    /** 拉取已产生的事件（非阻塞；无事件返回空列表）。 */
    List<RealtimeEvent> pollEvents();

    /** 打断：停止当前回合的输出（回合号由平台推进）。 */
    void interrupt(long turnNo);

    /** 关闭通道（幂等）。 */
    void close(RealtimeCloseReason reason);
}
