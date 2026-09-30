package com.basicframework.module.ai.service.realtime;

import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.framework.ai.core.realtime.RealtimeSessionChannel;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话通道注册表（X05）：**实例本地**的媒体/事件通道（真实媒体连接天然绑定到一个进程）。
 *
 * <p>职责边界（与会话行不同）：会话行（数据库）是跨实例事实，负责归属、到期、并发、幂等与审计；
 * 本注册表只在当前实例内存里保存"这条会话的通道还在不在"。通道不在而会话行仍在线时，
 * 平台按"已断线"处理（拒绝推流，要求重连建立通道），而不是假设另一台实例能替它送达。
 *
 * <p>关闭语义：先更新状态（数据库事实），再关闭通道；通道关闭失败只记录稳定原因，
 * 不改变已经落库的终态——关闭一个已经死掉的传输没有可执行的回退动作。
 */
@Slf4j
@Component
public class AiRealtimeChannels {

    /**
     * 单实例本地通道上限（有界资源）。
     *
     * <p>为什么需要上限：通道是本实例资源，而会话的终态可能是**惰性**物化的（到期/断线超时只在
     * 被读取时关闭，不设常驻扫描任务）。被遗弃的会话不会自己来关通道，因此这里按"最旧先关"
     * 兜底：超过上限时关闭并移除最早登记的通道，保证单实例资源有界（宁可多关一条空闲通道，
     * 也不让本地句柄无限增长）。
     */
    public static final int MAX_LOCAL_CHANNELS = 256;

    private final Map<Long, RealtimeSessionChannel> channels = new ConcurrentHashMap<>();

    private final java.util.Deque<Long> registrationOrder = new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** 登记通道（同一会话重复登记时以最新为准：重连会重新打开通道）。 */
    public void register(Long sessionId, RealtimeSessionChannel channel) {
        if (sessionId == null || channel == null) {
            return;
        }
        RealtimeSessionChannel previous = channels.put(sessionId, channel);
        if (previous != null && previous != channel) {
            closeQuietly(previous, RealtimeCloseReason.ADAPTER_ENDED);
        }
        registrationOrder.addLast(sessionId);
        evictOldestBeyondLimit();
    }

    /** 关闭并移除超出上限的最旧通道（幂等：顺序记录里的重复键在通道已移除时是空操作）。 */
    private void evictOldestBeyondLimit() {
        while (channels.size() > MAX_LOCAL_CHANNELS) {
            Long oldest = registrationOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            close(oldest, RealtimeCloseReason.ADAPTER_ENDED);
        }
    }

    /** 当前实例上该会话的通道（不在本实例返回空）。 */
    public Optional<RealtimeSessionChannel> find(Long sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(channels.get(sessionId));
    }

    /** 关闭并移除通道（幂等）。 */
    public void close(Long sessionId, RealtimeCloseReason reason) {
        RealtimeSessionChannel channel = channels.remove(sessionId);
        if (channel != null) {
            closeQuietly(channel, reason);
        }
    }

    /** 当前本地通道数（有界性可观测）。 */
    public int size() {
        return channels.size();
    }

    private void closeQuietly(RealtimeSessionChannel channel, RealtimeCloseReason reason) {
        try {
            channel.close(reason);
        } catch (RuntimeException closingFailure) {
            // 关闭失败的传输没有可执行的回退：终态已在会话行落库，这里只留稳定原因的运行日志
            log.debug(
                    "实时会话通道关闭失败：reason={}，type={}",
                    reason.code(),
                    closingFailure.getClass().getSimpleName());
        }
    }
}
