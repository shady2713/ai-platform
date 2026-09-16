package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.mysql.user.AdminUserMapper;
import com.basicframework.module.system.service.auth.AdminAuthService;
import com.basicframework.module.system.service.auth.dto.AuthChangeExpiredPasswordDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 验证旧凭据条件更新和认证事务回滚后的独立审计记录。 */
class PasswordChangePersistenceIT extends AbstractPersistenceIntegrationTest {
    private final AdminUserMapper userMapper;
    private final AdminAuthService authService;

    @Autowired
    PasswordChangePersistenceIT(AdminUserMapper userMapper, AdminAuthService authService) {
        this.userMapper = userMapper;
        this.authService = authService;
    }

    @Test
    void staleCredentialCannotOverwriteCompletedPasswordChange() {
        jdbcTemplate.update(
                "UPDATE system_users SET password = ?, status = 0, must_change_password = b'1' WHERE id = 1",
                "original-hash");
        assertThat(userMapper.completePasswordChange(1L, "original-hash", "first-hash", true))
                .isEqualTo(1);
        assertThat(userMapper.completePasswordChange(1L, "original-hash", "second-hash", true))
                .isZero();
        assertThat(userMapper.selectById(1L).getPassword()).isEqualTo("first-hash");
        assertThat(userMapper.selectById(1L).getMustChangePassword()).isFalse();
    }

    @Test
    void disabledOrAlreadyRotatedAccountCannotUseExpiredPasswordUpdate() {
        jdbcTemplate.update(
                "UPDATE system_users SET password = ?, status = 1, must_change_password = b'1' WHERE id = 1",
                "original-hash");
        assertThat(userMapper.completePasswordChange(1L, "original-hash", "changed-hash", true))
                .isZero();
        jdbcTemplate.update("UPDATE system_users SET status = 0, must_change_password = b'0' WHERE id = 1");
        assertThat(userMapper.completePasswordChange(1L, "original-hash", "changed-hash", true))
                .isZero();
        assertThat(userMapper.selectById(1L).getPassword()).isEqualTo("original-hash");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedAnonymousPasswordChangeRetainsAuditAfterTransactionRollback() {
        String username = "missing-audit-rotation";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        jdbcTemplate.update("DELETE FROM system_login_log WHERE username = ?", username);
        try {
            AuthChangeExpiredPasswordDTO change = new AuthChangeExpiredPasswordDTO();
            change.setUsername(username);
            change.setOldPassword("old-unknown-password");
            change.setNewPassword("new-unknown-password");
            assertThatThrownBy(() -> authService.changeExpiredPassword(change)).isInstanceOf(ServiceException.class);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM system_login_log WHERE username = ? AND result <> 0",
                            Integer.class,
                            username))
                    .isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM system_login_log WHERE username = ?", username);
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
