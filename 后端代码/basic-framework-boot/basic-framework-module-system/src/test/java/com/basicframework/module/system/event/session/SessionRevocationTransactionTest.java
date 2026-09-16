package com.basicframework.module.system.event.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import com.basicframework.module.system.service.metrics.SecuritySignalMetrics;
import com.basicframework.module.system.service.session.UserSessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class SessionRevocationTransactionTest {

    @Test
    void deletedSessionsAreCountedOnlyAfterCommitAndNeverAfterRollback() {
        for (int completion :
                new int[] {TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK
                }) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                UserSessionService sessions = mock(UserSessionService.class);
                when(sessions.removeSessionsByUser(1L, 2)).thenReturn(3);
                SecuritySignalMetrics metrics = new SecuritySignalMetrics(registry);
                context.registerBean(TransactionalEventListenerFactory.class);
                context.registerBean(
                        UserSessionRevocationListener.class,
                        () -> new UserSessionRevocationListener(sessions, context));
                context.registerBean(
                        SessionRevocationMetricsListener.class, () -> new SessionRevocationMetricsListener(metrics));
                context.refresh();
                TransactionSynchronizationManager.initSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                try {
                    context.publishEvent(new UserSessionRevocationEvent(
                            Set.of(1L, 2L), 2, UserSessionRevocationReasonEnum.PASSWORD_CHANGED));
                    TransactionSynchronizationUtils.triggerBeforeCommit(false);
                    assertThat(registry.find(SecuritySignalMetrics.SESSION_REVOCATIONS)
                                    .counters())
                            .isEmpty();
                    TransactionSynchronizationUtils.triggerAfterCompletion(completion);
                    if (completion == TransactionSynchronization.STATUS_COMMITTED) {
                        assertThat(registry.get(SecuritySignalMetrics.SESSION_REVOCATIONS)
                                        .counter()
                                        .count())
                                .isEqualTo(3);
                    } else {
                        assertThat(registry.find(SecuritySignalMetrics.SESSION_REVOCATIONS)
                                        .counters())
                                .isEmpty();
                    }
                } finally {
                    TransactionSynchronizationManager.clearSynchronization();
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                }
            } finally {
                registry.close();
            }
        }
    }
}
