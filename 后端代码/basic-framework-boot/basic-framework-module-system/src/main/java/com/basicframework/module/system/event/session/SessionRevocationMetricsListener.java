package com.basicframework.module.system.event.session;

import com.basicframework.module.system.service.metrics.SecuritySignalMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把会话撤销计入安全信号指标（威胁模型 T3）。
 *
 * <p>刻意与 {@link UserSessionRevocationListener} 分离：撤销动作必须在提交前完成，
 * 才能保证身份或权限变更与会话失效同成败；而指标只应反映真正落库的结果，
 * 因此按 {@code AFTER_COMMIT} 统计，并在无事务时通过 fallback 立即执行。
 */
@Component
@RequiredArgsConstructor
public class SessionRevocationMetricsListener {

    private final SecuritySignalMetrics securitySignalMetrics;

    /**
     * 计入一次会话撤销事件。
     *
     * @param event 会话撤销事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEvent(SessionRevocationCompletedEvent event) {
        securitySignalMetrics.recordSessionRevocation(event.reason(), event.deletedCount());
    }
}
