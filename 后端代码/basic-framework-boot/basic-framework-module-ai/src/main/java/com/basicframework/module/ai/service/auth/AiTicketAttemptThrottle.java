package com.basicframework.module.ai.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUOTA_EXCEEDED;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 换票失败节流（A04）：按客户端键（IP + appCode）限制**失败频率**，防暴力猜凭据。
 *
 * <p>实现是进程内滑动窗口（默认 60 秒内最多 10 次失败），键数有上限并做惰性淘汰：
 * 不依赖外部存储，跨节点的全局限流由部署侧的网关/入口限流承担（见证据文档"未验证项"）。
 * 节流只统计凭据/断言失败的尝试，成功换票会清零计数。
 */
@Component
public class AiTicketAttemptThrottle {

    /** 同一窗口允许的最大失败次数。 */
    private static final int DEFAULT_MAX_FAILURES = 10;

    /** 追踪的客户端键上限，超出时惰性淘汰最旧条目，避免内存无界。 */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private final AtomicLong operations = new AtomicLong();

    private final int maxFailures;

    private final Duration window;

    public AiTicketAttemptThrottle(
            @Value("${basic-framework.ai.ticket.max-failures-per-window:10}") int maxFailures,
            @Value("${basic-framework.ai.ticket.failure-window:PT1M}") Duration window) {
        this.maxFailures = maxFailures <= 0 ? DEFAULT_MAX_FAILURES : maxFailures;
        this.window = window == null || window.isZero() || window.isNegative() ? Duration.ofMinutes(1) : window;
    }

    /** 尝试换票前调用：超过失败阈值直接 429。 */
    public void checkAllowed(String clientKey) {
        Window tracked = windows.get(clientKey);
        if (tracked == null) {
            return;
        }
        if (tracked.isExpired(window)) {
            windows.remove(clientKey);
            return;
        }
        if (tracked.failures() >= maxFailures) {
            throw exception(AI_QUOTA_EXCEEDED);
        }
    }

    /** 换票失败后调用。 */
    public void recordFailure(String clientKey) {
        evictIfNeeded();
        windows.computeIfAbsent(clientKey, key -> new Window()).increment();
    }

    /** 换票成功后调用：清零该客户端的失败计数。 */
    public void recordSuccess(String clientKey) {
        windows.remove(clientKey);
    }

    /** 当前追踪的客户端键数量（观测与测试用）。 */
    public int trackedKeys() {
        return windows.size();
    }

    private void evictIfNeeded() {
        if (windows.size() < MAX_TRACKED_KEYS || operations.incrementAndGet() % 64 != 0) {
            return;
        }
        windows.entrySet().removeIf(entry -> entry.getValue().isExpired(window));
    }

    /** 单客户端失败窗口。 */
    private static final class Window {

        private int failures;

        private Instant firstFailure = Instant.now();

        private synchronized void increment() {
            failures++;
        }

        private synchronized int failures() {
            return failures;
        }

        private synchronized boolean isExpired(Duration window) {
            if (failures == 0) {
                return true;
            }
            return firstFailure.plus(window).isBefore(Instant.now());
        }
    }
}
