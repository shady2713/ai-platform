package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.enums.LogRecordConstants;
import com.basicframework.module.system.enums.permission.RoleTypeEnum;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import com.basicframework.module.system.service.session.UserSessionService;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 使用真实 MySQL/Redis 验证会话撤销、刷新失效、审计与会话表契约。 */
class SessionPersistenceIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserSessionService userSessionService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private RoleService roleService;

    @Test
    void sessionSchema_succeedsAgainstRealDatabase() {
        verifySessionTokenColumnWidths();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void disablingUser_revokesAccessAndRefreshTokensAgainstRealServices() {
        // 目标为自建无特权普通用户，operator 使用超管种子账号(1L)：
        // 与 update-status 链路的特权校验语义一致（普通目标不触发超管保护，但自操作仍被禁止）
        String targetUsername =
                "it_session_disable_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                """
                INSERT INTO system_users (username, password, nickname, sex, status, must_change_password)
                VALUES (?, 'session-integration-hash', '禁用集成目标', 1, 0, b'0')
                """,
                targetUsername);
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM system_users WHERE username = ?", Long.class, targetUsername);
        long operatorId = 1L;
        try {
            UserSessionDO token = null;
            try {
                token = createSession(userId);
                String accessToken = token.getAccessToken();
                String refreshToken = token.getRefreshToken();
                String accessTokenHash = token.getAccessTokenHash();
                String refreshTokenHash = token.getRefreshTokenHash();
                assertThat(userSessionService.checkAccessToken(accessToken)).isNotNull();

                MockHttpServletRequest auditRequest =
                        new MockHttpServletRequest("PUT", "/admin-api/system/user/update-status");
                auditRequest.setRemoteAddr("192.0.2.10");
                auditRequest.addHeader("User-Agent", "integration-test");
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(auditRequest));
                LoginUser loginUser = new LoginUser();
                loginUser.setId(operatorId);
                loginUser.setUserType(UserTypeEnum.ADMIN.getValue());
                SecurityFrameworkUtils.setLoginUser(loginUser, auditRequest);

                adminUserService.updateUserStatus(operatorId, userId, CommonStatusEnum.DISABLE.getStatus());

                await().atMost(Duration.ofSeconds(5))
                        .pollInterval(Duration.ofMillis(50))
                        .untilAsserted(() -> {
                            assertThat(jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM system_user_session WHERE access_token_hash = ? OR refresh_token_hash = ?",
                                            Integer.class,
                                            accessTokenHash,
                                            refreshTokenHash))
                                    .isZero();
                            assertThat(userSessionService.getSessionByAccessToken(accessToken))
                                    .isNull();
                        });
                await().atMost(Duration.ofSeconds(5))
                        .pollInterval(Duration.ofMillis(50))
                        .untilAsserted(() -> assertStatusAuditPersisted(operatorId, userId));
                assertServiceException(
                        ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode(),
                        () -> userSessionService.refreshSession(refreshToken));
            } finally {
                if (token != null) {
                    userSessionService.removeSessionByAccessToken(token.getAccessToken());
                }
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
            }
        } finally {
            userSessionService.removeSessionsByUser(userId, UserTypeEnum.ADMIN.getValue());
            jdbcTemplate.update("DELETE FROM system_operate_log WHERE biz_id = ?", userId);
            jdbcTemplate.update("DELETE FROM system_users WHERE id = ?", userId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void passwordAndRoleChanges_revokeSessionsAgainstRealServices() {
        long userId = 1L;
        AdminUserDO originalUser = prepareSessionUser(userId);
        Set<Long> originalRoleIds = permissionService.getUserRoleIdListByUserId(userId);
        Long temporaryRoleId = null;
        try {
            UserSessionDO passwordToken = createSession(userId);
            // operator 与目标同为超管种子账号：自操作放行，超管操作者校验通过
            adminUserService.updateUserPassword(userId, userId, "integration-password-123");
            awaitTokenRevoked(passwordToken);
            awaitAnonymousAuditPersisted(LogRecordConstants.SYSTEM_USER_UPDATE_PASSWORD_SUB_TYPE, userId);

            assertServiceException(ErrorCodeConstants.AUTH_PASSWORD_EXPIRED.getCode(), () -> createSession(userId));
            adminUserService.completePasswordChange(userId, "integration-rotated-password-123");
            UserSessionDO roleToken = createSession(userId);
            String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
            temporaryRoleId = roleService.createRole(
                    new RoleDO()
                            .setName("会话集成" + uniqueSuffix)
                            .setCode("it_session_" + uniqueSuffix)
                            .setSort(999),
                    RoleTypeEnum.CUSTOM.getType());
            Set<Long> changedRoleIds = new HashSet<>(originalRoleIds);
            changedRoleIds.add(temporaryRoleId);
            permissionService.assignUserRole(userId, userId, changedRoleIds);
            awaitTokenRevoked(roleToken);
            awaitAnonymousAuditPersisted(LogRecordConstants.SYSTEM_PERMISSION_ASSIGN_USER_ROLE_SUB_TYPE, userId);
        } finally {
            try {
                permissionService.assignUserRole(userId, userId, originalRoleIds);
                if (temporaryRoleId != null) {
                    roleService.deleteRole(temporaryRoleId);
                }
            } finally {
                restoreSessionUser(originalUser);
                jdbcTemplate.update("DELETE FROM system_operate_log WHERE biz_id = '1'");
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void privilegedTarget_rejectsMutationByPlainOperatorAgainstRealServices() {
        // 无任何角色的 operator 对持超管角色的目标(1L)发起禁用/删除：
        // 特权校验经真实角色表查询拒绝，且目标状态与会话不受影响
        String operatorUsername =
                "it_plain_operator_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                """
                INSERT INTO system_users (username, password, nickname, sex, status, must_change_password)
                VALUES (?, 'session-integration-hash', '普通操作者', 1, 0, b'0')
                """,
                operatorUsername);
        long operatorId = jdbcTemplate.queryForObject(
                "SELECT id FROM system_users WHERE username = ?", Long.class, operatorUsername);
        AdminUserDO originalTarget = prepareSessionUser(1L);
        try {
            UserSessionDO targetSession = createSession(1L);
            assertServiceException(
                    ErrorCodeConstants.ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN.getCode(),
                    () -> adminUserService.updateUserStatus(operatorId, 1L, CommonStatusEnum.DISABLE.getStatus()));
            assertServiceException(
                    ErrorCodeConstants.ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN.getCode(),
                    () -> adminUserService.deleteUser(operatorId, 1L));
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM system_users WHERE id = 1", Integer.class))
                    .isZero();
            assertThat(userSessionService.checkAccessToken(targetSession.getAccessToken()))
                    .isNotNull();
        } finally {
            restoreSessionUser(originalTarget);
            jdbcTemplate.update("DELETE FROM system_operate_log WHERE biz_id = '1'");
            jdbcTemplate.update("DELETE FROM system_users WHERE id = ?", operatorId);
        }
    }

    private AdminUserDO prepareSessionUser(long userId) {
        AdminUserDO original = adminUserService.getUser(userId);
        jdbcTemplate.update(
                "UPDATE system_users SET password = ?, status = 0, must_change_password = b'0' WHERE id = ?",
                "session-integration-hash",
                userId);
        return original;
    }

    private void restoreSessionUser(AdminUserDO original) {
        userSessionService.removeSessionsByUser(original.getId(), UserTypeEnum.ADMIN.getValue());
        jdbcTemplate.update(
                "UPDATE system_users SET password = ?, status = ?, must_change_password = ? WHERE id = ?",
                original.getPassword(),
                original.getStatus(),
                original.getMustChangePassword(),
                original.getId());
    }

    private UserSessionDO createSession(long userId) {
        return userSessionService.createSession(
                userId,
                UserTypeEnum.ADMIN.getValue(),
                adminUserService.getUser(userId).getPassword());
    }

    private void awaitTokenRevoked(UserSessionDO token) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    assertThat(jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM system_user_session WHERE access_token_hash = ? OR refresh_token_hash = ?",
                                    Integer.class,
                                    token.getAccessTokenHash(),
                                    token.getRefreshTokenHash()))
                            .isZero();
                    assertThat(userSessionService.getSessionByAccessToken(token.getAccessToken()))
                            .isNull();
                    assertServiceException(
                            ErrorCodeConstants.SESSION_REFRESH_TOKEN_INVALID.getCode(),
                            () -> userSessionService.refreshSession(token.getRefreshToken()));
                });
    }

    private void awaitAnonymousAuditPersisted(String subType, long bizId) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    AnonymousAuditActor actor = jdbcTemplate.queryForObject(
                            """
                            SELECT user_id, user_type
                            FROM system_operate_log
                            WHERE sub_type = ? AND biz_id = ?
                            ORDER BY id DESC
                            LIMIT 1
                            """,
                            (resultSet, rowNum) -> new AnonymousAuditActor(
                                    resultSet.getObject("user_id", Long.class), resultSet.getInt("user_type")),
                            subType,
                            bizId);
                    assertThat(actor).isEqualTo(new AnonymousAuditActor(null, UserTypeEnum.SYSTEM.getValue()));
                });
    }

    private record AnonymousAuditActor(Long userId, int userType) {}

    private void assertStatusAuditPersisted(long operatorId, long userId) {
        StatusAuditRow audit = jdbcTemplate.queryForObject(
                """
                SELECT user_id, user_type, biz_id, action, request_method, request_url, user_ip, user_agent
                FROM system_operate_log
                WHERE type = ? AND sub_type = ? AND biz_id = ?
                ORDER BY id DESC
                LIMIT 1
                """,
                (resultSet, rowNum) -> new StatusAuditRow(
                        resultSet.getLong("user_id"),
                        resultSet.getInt("user_type"),
                        resultSet.getLong("biz_id"),
                        resultSet.getString("action"),
                        resultSet.getString("request_method"),
                        resultSet.getString("request_url"),
                        resultSet.getString("user_ip"),
                        resultSet.getString("user_agent")),
                LogRecordConstants.SYSTEM_USER_TYPE,
                LogRecordConstants.SYSTEM_USER_UPDATE_STATUS_SUB_TYPE,
                userId);
        assertThat(audit)
                .isEqualTo(new StatusAuditRow(
                        operatorId,
                        UserTypeEnum.ADMIN.getValue(),
                        userId,
                        "将用户【禁用集成目标】的状态修改为【关闭】",
                        "PUT",
                        "/admin-api/system/user/update-status",
                        "192.0.2.10",
                        "integration-test"));
    }

    private record StatusAuditRow(
            long userId,
            int userType,
            long bizId,
            String action,
            String requestMethod,
            String requestUrl,
            String userIp,
            String userAgent) {}

    private void verifySessionTokenColumnWidths() {
        assertThat(queryColumnCharacterLength("system_user_session", "access_token_hash"))
                .isEqualTo(64L);
        assertThat(queryColumnCharacterLength("system_user_session", "refresh_token_hash"))
                .isEqualTo(64L);
        assertThat(queryColumnCharacterLength("system_user_session", "refresh_family_hash"))
                .isEqualTo(64L);
        assertThat(queryColumnNullable("system_user_session", "refresh_family_hash"))
                .isEqualTo("YES");
        assertThat(queryColumnNullable("system_user_session", "user_id")).isEqualTo("NO");
    }

    private Long queryColumnCharacterLength(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT CHARACTER_MAXIMUM_LENGTH
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """,
                Long.class,
                tableName,
                columnName);
    }

    private String queryColumnNullable(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT IS_NULLABLE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """,
                String.class,
                tableName,
                columnName);
    }
}
