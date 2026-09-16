package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.enums.logger.LoginResultEnum;
import com.basicframework.module.system.service.logger.LoginLogService;
import com.basicframework.module.system.service.logger.dto.LoginLogCreateReqDTO;
import com.basicframework.module.system.service.session.UserSessionService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实数据库行锁与事务边界验证，不以缓存删除或事件发布的自报结果作为撤销证据。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionConcurrencyIT extends AbstractPersistenceIntegrationTest {

    private static final String INITIAL_HASH = "session-concurrency-original-hash";

    @Autowired
    private UserSessionService sessionService;

    @Autowired
    private AdminUserService userService;

    @Autowired
    private LoginLogService loginLogService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long userId;
    private String username;
    private ExecutorService workers;

    @BeforeEach
    void createIsolatedAccount() {
        username = "it_session_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO system_users (username, password, nickname, status, must_change_password) VALUES (?, ?, ?, 0, b'0')",
                username,
                INITIAL_HASH,
                "会话并发测试");
        userId = jdbcTemplate.queryForObject("SELECT id FROM system_users WHERE username = ?", Long.class, username);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void removeIsolatedAccount() throws InterruptedException {
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        jdbcTemplate.update("DELETE FROM system_user_session WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM system_login_log WHERE username = ?", username);
        jdbcTemplate.update("DELETE FROM system_operate_log WHERE biz_id = ?", userId);
        jdbcTemplate.update("DELETE FROM system_users WHERE id = ?", userId);
    }

    @Test
    void committedPasswordChangeRejectsIssuanceWaitingWithAnOlderCredential() throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> change =
                workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    userService.completePasswordChange(userId, "new-session-password-925");
                    changed.countDown();
                    awaitSignal(commit);
                }));
        try {
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            CountDownLatch attempted = new CountDownLatch(1);
            Future<UserSessionDO> issuance = workers.submit(() -> {
                attempted.countDown();
                return createSession();
            });
            assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> issuance.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            change.get(10, TimeUnit.SECONDS);
            assertFutureCode(issuance, ErrorCodeConstants.AUTH_LOGIN_BAD_CREDENTIALS.getCode());
            assertNoSession();
            assertThat(userService.getUser(userId).getMustChangePassword()).isFalse();
        } finally {
            commit.countDown();
        }
    }

    @Test
    void disablingUserSerializesWithRefreshAndRevokesBothCredentials() throws Exception {
        UserSessionDO session = createSession();
        CountDownLatch disabled = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> change =
                workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    userService.updateUserStatus(userId + 1, userId, CommonStatusEnum.DISABLE.getStatus());
                    disabled.countDown();
                    awaitSignal(commit);
                }));
        try {
            assertThat(disabled.await(10, TimeUnit.SECONDS)).isTrue();
            Future<UserSessionDO> refresh =
                    workers.submit(() -> sessionService.refreshSession(session.getRefreshToken()));
            assertThatThrownBy(() -> refresh.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            change.get(10, TimeUnit.SECONDS);
            assertFutureCode(refresh, ErrorCodeConstants.AUTH_LOGIN_USER_DISABLED.getCode());
            assertNoSession();
            assertServiceException(
                    ErrorCodeConstants.SESSION_ACCESS_TOKEN_NOT_EXISTS.getCode(),
                    () -> sessionService.checkAccessToken(session.getAccessToken()));
        } finally {
            commit.countDown();
        }
    }

    @Test
    void concurrentRefreshPublishesExactlyOneReplacement() throws Exception {
        UserSessionDO session = createSession();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UserSessionDO>> attempts = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            attempts.add(workers.submit(() -> {
                awaitSignal(start);
                return sessionService.refreshSession(session.getRefreshToken());
            }));
        }
        start.countDown();
        List<UserSessionDO> issued = new ArrayList<>();
        int rejected = 0;
        for (Future<UserSessionDO> attempt : attempts) {
            try {
                issued.add(attempt.get(10, TimeUnit.SECONDS));
            } catch (ExecutionException exception) {
                assertThat(exception.getCause())
                        .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                                .isEqualTo(ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode()));
                rejected++;
            }
        }
        assertThat(issued).hasSize(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(sessionService.getSessionByAccessToken(session.getAccessToken()))
                .isNull();
        assertThat(sessionService
                        .checkAccessToken(issued.get(0).getAccessToken())
                        .getId())
                .isEqualTo(session.getId());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_user_session WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void outerRollbackRemovesSessionAndSuccessAuditButRetainsFailureAudit() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            createSession();
            loginLogService.createLoginLog(loginLog(LoginResultEnum.SUCCESS));
            loginLogService.createLoginLog(loginLog(LoginResultEnum.BAD_CREDENTIALS));
            status.setRollbackOnly();
        });
        assertNoSession();
        assertThat(jdbcTemplate.queryForList(
                        "SELECT result FROM system_login_log WHERE username = ?", Integer.class, username))
                .containsExactly(LoginResultEnum.BAD_CREDENTIALS.getResult());
    }

    @Test
    void oldCookieRevokesItsSessionAfterMultipleCommittedRefreshes() {
        UserSessionDO initial = createSession();
        UserSessionDO unrelated = createSession();
        UserSessionDO first = sessionService.refreshSession(initial.getRefreshToken());
        UserSessionDO current = sessionService.refreshSession(first.getRefreshToken());
        // 旧代令牌重放刷新按重用检测（RFC 6819）吊销所属会话，无需再显式登出
        assertServiceException(
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode(),
                () -> sessionService.refreshSession(initial.getRefreshToken()));
        assertRevoked(current);
        assertThat(sessionService.checkAccessToken(unrelated.getAccessToken()).getId())
                .isEqualTo(unrelated.getId());
    }

    @Test
    void stablePrefixReplayRevokesTheSession() {
        UserSessionDO session = createSession();
        // 稳定撤销凭据（令牌前缀）单独重放刷新同样触发重用检测
        assertServiceException(
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode(),
                () -> sessionService.refreshSession(session.getRefreshToken().substring(0, 64)));
        assertNoSession();
    }

    @Test
    void legacyCookieCanRevokeBeforeAndAfterUpgrade() {
        UserSessionDO before = createSession();
        String oldCookie = convertToLegacySession(before);
        assertThat(sessionService.removeSessionByRefreshToken(oldCookie).getId())
                .isEqualTo(before.getId());
        UserSessionDO upgrade = createSession();
        String legacyCookie = convertToLegacySession(upgrade);
        UserSessionDO current = sessionService.refreshSession(legacyCookie);
        assertThat(current.getRefreshToken()).hasSize(128).startsWith(legacyCookie);
        assertThat(current.getRefreshFamilyHash()).isNotNull();
        assertThat(sessionService.removeSessionByRefreshToken(legacyCookie).getId())
                .isEqualTo(upgrade.getId());
        assertRevoked(current);
        assertNoSession();
    }

    @Test
    void logoutRacingLegacyUpgradeCannotLeaveTheReplacementSessionAlive() throws Exception {
        String legacyCookie = convertToLegacySession(createSession());
        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Future<UserSessionDO> rotation =
                workers.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    UserSessionDO replacement = sessionService.refreshSession(legacyCookie);
                    rotated.countDown();
                    awaitSignal(commit);
                    return replacement;
                }));
        try {
            assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();
            Future<UserSessionDO> logout =
                    workers.submit(() -> sessionService.removeSessionByRefreshToken(legacyCookie));
            assertThatThrownBy(() -> logout.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            UserSessionDO replacement = rotation.get(10, TimeUnit.SECONDS);
            assertThat(logout.get(10, TimeUnit.SECONDS).getId()).isEqualTo(replacement.getId());
            assertRevoked(replacement);
            assertNoSession();
        } finally {
            commit.countDown();
        }
    }

    private String convertToLegacySession(UserSessionDO session) {
        String legacy = session.getRefreshToken().substring(0, 64);
        jdbcTemplate.update(
                "UPDATE system_user_session SET refresh_token_hash = SHA2(?, 256), refresh_family_hash = NULL WHERE id = ?",
                legacy,
                session.getId());
        return legacy;
    }

    private void assertRevoked(UserSessionDO session) {
        assertServiceException(
                ErrorCodeConstants.SESSION_ACCESS_TOKEN_NOT_EXISTS.getCode(),
                () -> sessionService.checkAccessToken(session.getAccessToken()));
        assertServiceException(
                ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode(),
                () -> sessionService.refreshSession(session.getRefreshToken()));
    }

    private UserSessionDO createSession() {
        return sessionService.createSession(userId, UserTypeEnum.ADMIN.getValue(), INITIAL_HASH);
    }

    private LoginLogCreateReqDTO loginLog(LoginResultEnum result) {
        LoginLogCreateReqDTO log = new LoginLogCreateReqDTO();
        log.setLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType());
        log.setUserId(userId);
        log.setUserType(UserTypeEnum.ADMIN.getValue());
        log.setUsername(username);
        log.setResult(result.getResult());
        log.setUserIp("192.0.2.10");
        log.setUserAgent("session-integration");
        return log;
    }

    private void assertNoSession() {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_user_session WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    private static void assertFutureCode(Future<?> future, int expectedCode) {
        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(expectedCode));
    }

    private static void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发测试未收到释放信号");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", exception);
        }
    }
}
