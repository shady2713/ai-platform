package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.service.session.UserSessionService;
import com.basicframework.module.system.service.user.AdminUserService;
import com.basicframework.module.system.service.user.dto.UserImportDTO;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 验证完整表单的清空语义与岗位双存储的一致性，保留真实 Mapper、事务和事件监听器。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserProfilePersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final String PASSWORD_HASH = "profile-integration-original-hash";
    private static final String AVATAR = "https://example.com/profile.png";

    @Autowired
    private AdminUserService userService;

    @Autowired
    private UserSessionService sessionService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long userId;
    private long deptId;
    private long postId;
    private String username;
    private String email;
    private String mobile;

    @BeforeEach
    void createIsolatedUserWithDepartmentAndPost() {
        username = "it_profile_" + UUID.randomUUID().toString().substring(0, 8);
        email = username + "@example.com";
        jdbcTemplate.update("INSERT INTO system_dept (name, status) VALUES (?, 0)", username);
        deptId = jdbcTemplate.queryForObject("SELECT id FROM system_dept WHERE name = ?", Long.class, username);
        jdbcTemplate.update(
                "INSERT INTO system_post (code, name, sort, status) VALUES (?, ?, 0, 0)", username, username);
        postId = jdbcTemplate.queryForObject("SELECT id FROM system_post WHERE code = ?", Long.class, username);
        jdbcTemplate.update(
                """
                INSERT INTO system_users
                    (username, password, nickname, dept_id, post_ids, email, remark, sex, avatar, status, must_change_password)
                VALUES (?, ?, ?, ?, ?, ?, '保留备注', 1, ?, 0, b'0')
                """,
                username,
                PASSWORD_HASH,
                "资料测试用户",
                deptId,
                "[" + postId + "]",
                email,
                AVATAR);
        userId = jdbcTemplate.queryForObject("SELECT id FROM system_users WHERE username = ?", Long.class, username);
        mobile = "139" + String.format("%08d", userId);
        jdbcTemplate.update("UPDATE system_users SET mobile = ? WHERE id = ?", mobile, userId);
        jdbcTemplate.update("INSERT INTO system_user_post (user_id, post_id) VALUES (?, ?)", userId, postId);
    }

    @AfterEach
    void removeIsolatedRows() {
        jdbcTemplate.update("DELETE FROM system_user_session WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM system_user_post WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM system_operate_log WHERE biz_id = ?", userId);
        jdbcTemplate.update("DELETE FROM system_users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM system_post WHERE code IN (?, ?)", username, username + "_other");
        jdbcTemplate.update("DELETE FROM system_dept WHERE id = ?", deptId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void managedFormClearsNullableFieldsAndBothPostRepresentations(boolean omittedPosts) {
        sessionService.createSession(userId, UserTypeEnum.ADMIN.getValue(), PASSWORD_HASH);
        AdminUserDO form = managedForm()
                .setPostIds(omittedPosts ? null : Set.of())
                .setEmail("  ")
                .setMobile("")
                .setPassword("untrusted-password")
                .setMustChangePassword(true);

        userService.updateUser(userId + 1, form);

        AdminUserDO saved = userService.getUser(userId);
        assertThat(saved.getDeptId()).isNull();
        assertThat(saved.getEmail()).isNull();
        assertThat(saved.getMobile()).isNull();
        assertThat(saved.getRemark()).isNull();
        assertThat(saved.getPostIds()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT post_ids FROM system_users WHERE id = ?", String.class, userId))
                .isEqualTo("[]");
        assertThat(postRelations()).isEmpty();
        assertCredentialAndAvatarUnchanged(saved);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_user_session WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    @Test
    void profileUnbindsContactsWhilePreservingDepartmentPostsAndCredentials() {
        userService.updateUserProfile(
                userId,
                new AdminUserDO()
                        .setNickname("资料测试用户")
                        .setEmail(" ")
                        .setMobile(" ")
                        .setDeptId(0L)
                        .setPostIds(Set.of())
                        .setPassword("untrusted-password")
                        .setMustChangePassword(true)
                        .setStatus(1));

        AdminUserDO saved = userService.getUser(userId);
        assertThat(saved.getEmail()).isNull();
        assertThat(saved.getMobile()).isNull();
        assertThat(saved.getDeptId()).isEqualTo(deptId);
        assertThat(saved.getPostIds()).containsExactly(postId);
        assertThat(postRelations()).containsExactly(postId);
        assertThat(saved.getSex()).isEqualTo(1);
        assertThat(saved.getRemark()).isEqualTo("保留备注");
        assertCredentialAndAvatarUnchanged(saved);
    }

    @Test
    void outerRollbackRestoresUserAndPostRelationsTogether() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            userService.updateUser(userId + 1, managedForm());
            assertThat(userService.getUser(userId).getPostIds()).isEmpty();
            assertThat(postRelations()).isEmpty();
            status.setRollbackOnly();
        });

        AdminUserDO saved = userService.getUser(userId);
        assertThat(saved.getPostIds()).containsExactly(postId);
        assertThat(postRelations()).containsExactly(postId);
        assertThat(saved.getDeptId()).isEqualTo(deptId);
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getMobile()).isEqualTo(mobile);
    }

    @Test
    void importBlankCellsPreserveExistingDepartmentContactsPostsAndPassword() {
        UserImportDTO row = new UserImportDTO();
        row.setUsername(username);
        row.setNickname("导入更新资料");
        row.setDeptName(" ");
        row.setEmail(" ");
        row.setMobile(" ");

        assertThat(userService.importUserList(userId + 1, List.of(row), true).getUpdateUsernames())
                .containsExactly(username);

        AdminUserDO saved = userService.getUser(userId);
        assertThat(saved.getDeptId()).isEqualTo(deptId);
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getMobile()).isEqualTo(mobile);
        assertThat(saved.getPostIds()).containsExactly(postId);
        assertThat(postRelations()).containsExactly(postId);
        assertThat(saved.getNickname()).isEqualTo("导入更新资料");
        assertCredentialAndAvatarUnchanged(saved);
    }

    @Test
    void olderTransactionSnapshotCannotPreserveSupersededPostsOrIntermediateSession() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO system_post (code, name, sort, status) VALUES (?, ?, 0, 0)", username + "_other", "临时岗位");
        Long otherPostId = jdbcTemplate.queryForObject(
                "SELECT id FROM system_post WHERE code = ?", Long.class, username + "_other");
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> waitingEdit =
                    worker.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        assertThat(userService.getUser(userId).getDeptId()).isEqualTo(deptId);
                        snapshotRead.countDown();
                        awaitSignal(proceed);
                        userService.updateUser(
                                userId + 1, managedForm().setDeptId(deptId).setPostIds(Set.of(postId)));
                    }));
            assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            userService.updateUser(userId + 1, managedForm().setPostIds(Set.of(otherPostId)));
            UserSessionDO intermediateSession =
                    sessionService.createSession(userId, UserTypeEnum.ADMIN.getValue(), PASSWORD_HASH);
            proceed.countDown();
            waitingEdit.get(10, TimeUnit.SECONDS);

            assertThat(userService.getUser(userId).getDeptId()).isEqualTo(deptId);
            assertThat(userService.getUser(userId).getPostIds()).containsExactly(postId);
            assertThat(postRelations()).containsExactly(postId);
            assertServiceException(
                    ErrorCodeConstants.SESSION_ACCESS_TOKEN_NOT_EXISTS.getCode(),
                    () -> sessionService.checkAccessToken(intermediateSession.getAccessToken()));
        } finally {
            proceed.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void staleSnapshotCannotReportPasswordResetOrImportSuccessAfterDeletion(boolean adminReset) throws Exception {
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> attemptedWrite = worker.submit(() -> assertServiceException(
                    ErrorCodeConstants.USER_NOT_EXISTS.getCode(),
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        assertThat(userService.getUser(userId)).isNotNull();
                        snapshotRead.countDown();
                        awaitSignal(proceed);
                        if (adminReset) {
                            userService.updateUserPassword(userId + 1, userId, "independent-profile-password-925");
                        } else {
                            UserImportDTO row =
                                    new UserImportDTO().setUsername(username).setNickname("不应写入的昵称");
                            userService.importUserList(userId + 1, List.of(row), true);
                        }
                    })));
            assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            userService.deleteUser(userId + 1, userId);
            proceed.countDown();
            attemptedWrite.get(10, TimeUnit.SECONDS);

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT deleted FROM system_users WHERE id = ?", Boolean.class, userId))
                    .isTrue();
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT password FROM system_users WHERE id = ?", String.class, userId))
                    .isEqualTo(PASSWORD_HASH);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT nickname FROM system_users WHERE id = ?", String.class, userId))
                    .isEqualTo("资料测试用户");
        } finally {
            proceed.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("资料编辑测试未收到释放信号");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("资料编辑测试被中断", exception);
        }
    }

    private AdminUserDO managedForm() {
        return new AdminUserDO().setId(userId).setUsername(username).setNickname("资料测试用户");
    }

    private List<Long> postRelations() {
        return jdbcTemplate.queryForList("SELECT post_id FROM system_user_post WHERE user_id = ?", Long.class, userId);
    }

    private static void assertCredentialAndAvatarUnchanged(AdminUserDO saved) {
        assertThat(saved.getPassword()).isEqualTo(PASSWORD_HASH);
        assertThat(saved.getMustChangePassword()).isFalse();
        assertThat(saved.getStatus()).isZero();
        assertThat(saved.getAvatar()).isEqualTo(AVATAR);
    }
}
