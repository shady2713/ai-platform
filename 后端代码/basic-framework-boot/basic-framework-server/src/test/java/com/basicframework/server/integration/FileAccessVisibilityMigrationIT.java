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

/** 使用真实 MySQL 验证 V37 为历史文件保留公开语义，并限制新读取策略取值。 */
@Testcontainers
class FileAccessVisibilityMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v37_preservesLegacyPublicReadsAndBackfillsKnownOwners() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("36")).migrate().migrationsExecuted)
                // 迁移序列无 V7：到 36 为止共执行 6 + (36-7) = 35 个迁移，与其他 IT 的计数口径一致
                .isEqualTo(35);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        long configId = 9_037_001L;
        insertFileConfig(jdbcTemplate, configId);
        insertLegacyFile(jdbcTemplate, 9_037_001L, configId, 7L, 2);
        insertLegacyFile(jdbcTemplate, 9_037_002L, configId, null, null);

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("37")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT access_type FROM infra_file WHERE id = ?", Integer.class, 9_037_001L))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT owner_user_id FROM infra_file WHERE id = ?", Long.class, 9_037_001L))
                .isEqualTo(7L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT owner_user_type FROM infra_file WHERE id = ?", Integer.class, 9_037_001L))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT access_type FROM infra_file WHERE id = ?", Integer.class, 9_037_002L))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT owner_user_id FROM infra_file WHERE id = ?", Long.class, 9_037_002L))
                .isNull();
        // MySQL 3819（check constraint 被违反）在 Spring 的 MySQL 错误码翻译表中
        // 缺失，会包装成 UncategorizedSQLException 而非 DataIntegrityViolationException；
        // 这里钉住行为本身：约束必须拒绝 access_type=3
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE infra_file SET access_type = 3 WHERE id = ?", 9_037_001L))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_file_access_type");
    }

    private static void insertFileConfig(JdbcTemplate jdbcTemplate, long configId) {
        jdbcTemplate.update(
                """
                INSERT INTO infra_file_config (id, name, storage, master, config, deleted)
                VALUES (?, ?, 1, b'0', '{}', b'0')
                """,
                configId,
                "file-access-migration");
    }

    private static void insertLegacyFile(
            JdbcTemplate jdbcTemplate, long fileId, long configId, Long uploadUserId, Integer uploadUserType) {
        jdbcTemplate.update(
                """
                INSERT INTO infra_file (
                    id, config_id, name, path, url, type, size, upload_user_id, upload_user_type)
                VALUES (?, ?, ?, ?, 'http://localhost/files/migration', 'text/plain', 1, ?, ?)
                """,
                fileId,
                configId,
                "legacy-" + fileId + ".txt",
                "migration/legacy-" + fileId + ".txt",
                uploadUserId,
                uploadUserType);
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        return flywayConfiguration(dataSource).target(target).load();
    }

    private static FluentConfiguration flywayConfiguration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}
