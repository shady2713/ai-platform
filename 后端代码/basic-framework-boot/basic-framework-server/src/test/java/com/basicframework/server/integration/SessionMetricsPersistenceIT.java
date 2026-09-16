package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import com.basicframework.module.system.event.session.UserSessionRevocationPublisher;
import com.basicframework.module.system.service.metrics.SecuritySignalMetrics;
import com.basicframework.module.system.service.session.UserSessionService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 用真实 SQL 与事务验证撤销数量和指标提交语义。 */
class SessionMetricsPersistenceIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private UserSessionService sessions;

    @Autowired
    private UserSessionRevocationPublisher publisher;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void metricsMatchDeletedRowsAndIgnoreRollbackAndEmptyRevocations() {
        String username = "it_metrics_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO system_users (username,password,nickname,status,must_change_password) VALUES (?,?,'指标测试',0,b'0')",
                username,
                "session-integration-hash");
        long userId =
                jdbcTemplate.queryForObject("SELECT id FROM system_users WHERE username = ?", Long.class, username);
        var counter = registry.counter(SecuritySignalMetrics.SESSION_REVOCATIONS, "reason", "PASSWORD_CHANGED");
        double before = counter.count();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        try {
            sessions.createSession(userId, 2, "session-integration-hash");
            sessions.createSession(userId, 2, "session-integration-hash");
            transactions.executeWithoutResult(status -> {
                publisher.revokeAdminSession(userId, UserSessionRevocationReasonEnum.PASSWORD_CHANGED);
                status.setRollbackOnly();
            });
            assertThat(counter.count()).isEqualTo(before);
            assertThat(sessionCount(userId)).isEqualTo(2);
            transactions.executeWithoutResult(
                    status -> publisher.revokeAdminSession(userId, UserSessionRevocationReasonEnum.PASSWORD_CHANGED));
            assertThat(sessionCount(userId)).isZero();
            assertThat(counter.count()).isEqualTo(before + 2);
            transactions.executeWithoutResult(
                    status -> publisher.revokeAdminSession(userId, UserSessionRevocationReasonEnum.PASSWORD_CHANGED));
            assertThat(counter.count()).isEqualTo(before + 2);
        } finally {
            jdbcTemplate.update("DELETE FROM system_user_session WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM system_users WHERE id = ?", userId);
        }
    }

    private int sessionCount(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_user_session WHERE user_id = ?", Integer.class, userId);
    }
}
