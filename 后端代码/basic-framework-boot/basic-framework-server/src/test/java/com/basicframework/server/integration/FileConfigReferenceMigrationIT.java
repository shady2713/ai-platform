package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 MySQL 验证 V19 清理历史文件配置孤儿且保留有效引用。 */
@Testcontainers
class FileConfigReferenceMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v19_repairsHistoricalOrphansAndKeepsValidReferences() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("18")).migrate().migrationsExecuted)
                .isEqualTo(17);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        long activeConfigId = 9_076_001L;
        long deletedConfigId = 9_076_002L;
        long missingConfigId = 9_076_003L;
        insertConfig(jdbcTemplate, activeConfigId, false);
        insertConfig(jdbcTemplate, deletedConfigId, true);
        insertFile(jdbcTemplate, 9_076_001L, activeConfigId);
        insertFile(jdbcTemplate, 9_076_002L, deletedConfigId);
        insertFile(jdbcTemplate, 9_076_003L, missingConfigId);
        insertContent(jdbcTemplate, 9_076_001L, activeConfigId);
        insertContent(jdbcTemplate, 9_076_002L, deletedConfigId);
        insertContent(jdbcTemplate, 9_076_003L, missingConfigId);

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("19")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("SELECT config_id FROM infra_file WHERE id = ?", Long.class, 9_076_001L))
                .isEqualTo(activeConfigId);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT config_id FROM infra_file WHERE id IN (?, ?) ORDER BY id",
                        Long.class,
                        9_076_002L,
                        9_076_003L))
                .containsExactly(null, null);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM infra_file_content ORDER BY id", Long.class))
                .containsExactly(9_076_001L);
    }

    private static void insertConfig(JdbcTemplate jdbcTemplate, long id, boolean deleted) {
        jdbcTemplate.update(
                """
                INSERT INTO infra_file_config (id, name, storage, master, config, deleted)
                VALUES (?, ?, 1, b'0', '{}', ?)
                """,
                id,
                "migration-" + id,
                deleted);
    }

    private static void insertFile(JdbcTemplate jdbcTemplate, long id, long configId) {
        jdbcTemplate.update(
                """
                INSERT INTO infra_file (id, config_id, path, url, size)
                VALUES (?, ?, ?, 'http://localhost/migration', 1)
                """,
                id,
                configId,
                "migration/" + id);
    }

    private static void insertContent(JdbcTemplate jdbcTemplate, long id, long configId) {
        jdbcTemplate.update(
                "INSERT INTO infra_file_content (id, config_id, path, content) VALUES (?, ?, ?, ?)",
                id,
                configId,
                "migration/" + id,
                new byte[] {1});
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        return flywayConfiguration(dataSource).target(target).load();
    }

    private static FluentConfiguration flywayConfiguration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}
