package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 联系方式唯一性由真实 MySQL 约束，覆盖跳过服务预查询的并发写入。 */
class UserContactIntegrityIT extends AbstractPersistenceIntegrationTest {

    @Test
    void optionalContactsDefaultToNullAndAllowMultipleAccounts() {
        for (int index = 0; index < 3; index++) {
            String username = username();
            jdbcTemplate.update(
                    "INSERT INTO system_users (username, nickname, status) VALUES (?, '联系方式测试', 1)", username);
            var contacts =
                    jdbcTemplate.queryForMap("SELECT mobile, email FROM system_users WHERE username = ?", username);
            assertThat(contacts).containsEntry("mobile", null).containsEntry("email", null);
        }
        insertUser(username(), "", " ");
        insertUser(username(), " ", "");
    }

    @Test
    void activeContactsAreUniqueButCanBeReusedAfterMultipleSoftDeletes() {
        for (int index = 0; index < 3; index++) {
            String owner = username();
            insertUser(owner, "13900000009", "contact-owner@example.test");
            assertThatThrownBy(() -> insertUser(username(), "13900000009", null))
                    .isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> insertUser(username(), null, "CONTACT-OWNER@example.test"))
                    .isInstanceOf(DuplicateKeyException.class);
            jdbcTemplate.update("UPDATE system_users SET deleted = b'1' WHERE username = ?", owner);
        }
        insertUser(username(), "13900000009", "contact-owner@example.test");
    }

    @Test
    void directWritesCannotBypassEmailUniquenessWithWhitespace() {
        insertUser(username(), null, "contact-trim@example.test");
        assertThatThrownBy(() -> insertUser(username(), null, " contact-trim@example.test "))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentContactAllocationCommitsExactlyOneOwner() throws Exception {
        List<String> users = List.of(username(), username());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> attempts = users.stream()
                    .map(user -> workers.submit(() -> {
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("未收到并发写入信号");
                        try {
                            insertUser(user, "13900000008", null);
                            return true;
                        } catch (DuplicateKeyException expectedConflict) {
                            return false;
                        }
                    }))
                    .toList();
            start.countDown();
            int committed = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(10, TimeUnit.SECONDS)) committed++;
            }
            assertThat(committed).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM system_users WHERE mobile = '13900000008' AND deleted = b'0'",
                            Integer.class))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update("DELETE FROM system_users WHERE username IN (?, ?)", users.get(0), users.get(1));
        }
    }

    private void insertUser(String username, String mobile, String email) {
        jdbcTemplate.update(
                "INSERT INTO system_users (username, nickname, status, mobile, email) VALUES (?, '联系方式测试', 1, ?, ?)",
                username,
                mobile,
                email);
    }

    private static String username() {
        return "it_contact_" + UUID.randomUUID().toString().substring(0, 12);
    }
}
