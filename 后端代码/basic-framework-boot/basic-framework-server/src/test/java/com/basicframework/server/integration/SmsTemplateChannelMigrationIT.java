package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 MySQL 验证 V23 清理短信模板渠道孤儿并增加强引用。 */
@Testcontainers
class SmsTemplateChannelMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v23_repairsInvalidReferencesAndAddsRestrictForeignKey() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("22")).migrate().migrationsExecuted)
                .isEqualTo(21);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        insertChannel(jdbcTemplate, 9_083_001L, "aliyun-v23-active", false);
        insertChannel(jdbcTemplate, 9_083_002L, "aliyun-v23-deleted", true);
        insertTemplate(jdbcTemplate, 9_083_011L, 9_083_001L, "SMS_V23_VALID", false);
        insertTemplate(jdbcTemplate, 9_083_012L, 9_083_099L, "SMS_V23_MISSING", false);
        insertTemplate(jdbcTemplate, 9_083_013L, 9_083_099L, "SMS_V23_MISSING_DELETED", true);
        insertTemplate(jdbcTemplate, 9_083_014L, 9_083_002L, "SMS_V23_DELETED_PARENT", false);

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("23")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(deleted(jdbcTemplate, 9_083_011L)).isFalse();
        assertThat(count(jdbcTemplate, 9_083_012L)).isZero();
        assertThat(count(jdbcTemplate, 9_083_013L)).isZero();
        assertThat(deleted(jdbcTemplate, 9_083_014L)).isTrue();
        assertThat(constraintCount(jdbcTemplate, "fk_sms_template_channel", "FOREIGN KEY"))
                .isEqualTo(1);
        assertThat(indexCount(jdbcTemplate, "idx_channel_id")).isEqualTo(1);

        assertThatThrownBy(() -> insertTemplate(jdbcTemplate, 9_083_015L, 9_083_099L, "SMS_V23_REJECT_ORPHAN", false))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_sms_template_channel");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM system_sms_channel WHERE id = ?", 9_083_001L))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_sms_template_channel");
    }

    private static void insertChannel(JdbcTemplate jdbcTemplate, long id, String code, boolean deleted) {
        jdbcTemplate.update(
                """
                INSERT INTO system_sms_channel (id, signature, code, status, api_key, deleted)
                VALUES (?, 'integration', ?, 0, 'integration-key', ?)
                """,
                id,
                code,
                deleted);
    }

    private static void insertTemplate(
            JdbcTemplate jdbcTemplate, long id, long channelId, String code, boolean deleted) {
        jdbcTemplate.update(
                """
                INSERT INTO system_sms_template
                    (id, type, status, code, name, content, params, api_template_id,
                     channel_id, channel_code, deleted)
                VALUES (?, 1, 0, ?, 'migration', '验证码 {code}', '["code"]',
                        'api-template', ?, 'aliyun', ?)
                """,
                id,
                code,
                channelId,
                deleted);
    }

    private static int count(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_sms_template WHERE id = ?", Integer.class, id);
    }

    private static boolean deleted(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted + 0 FROM system_sms_template WHERE id = ?", Boolean.class, id);
    }

    private static int constraintCount(JdbcTemplate jdbcTemplate, String name, String type) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'system_sms_template'
                  AND constraint_name = ?
                  AND constraint_type = ?
                """,
                Integer.class,
                name,
                type);
    }

    private static int indexCount(JdbcTemplate jdbcTemplate, String name) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'system_sms_template'
                  AND index_name = ?
                  AND column_name = 'channel_id'
                """,
                Integer.class,
                name);
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        return flywayConfiguration(dataSource).target(target).load();
    }

    private static FluentConfiguration flywayConfiguration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}
