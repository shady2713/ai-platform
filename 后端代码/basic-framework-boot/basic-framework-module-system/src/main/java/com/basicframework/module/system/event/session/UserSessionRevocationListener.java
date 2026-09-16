package com.basicframework.module.system.event.session;

import com.basicframework.module.system.service.session.UserSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 在业务事务提交前同步撤销用户会话，保证身份或权限变更与会话失效同成败。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UserSessionRevocationListener {

    private final UserSessionService userSessionService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 处理会话撤销事件。无事务的发布者通过 fallback 立即执行。
     *
     * @param event 会话撤销事件
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onEvent(UserSessionRevocationEvent event) {
        log.info("[onEvent][撤销用户({})会话，原因({})]", event.userIds(), event.reason());
        int deletedCount = event.userIds().stream()
                .sorted()
                .mapToInt(userId -> userSessionService.removeSessionsByUser(userId, event.userType()))
                .sum();
        if (deletedCount > 0) {
            eventPublisher.publishEvent(new SessionRevocationCompletedEvent(event.reason(), deletedCount));
        }
    }
}
