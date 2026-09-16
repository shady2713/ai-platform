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

/** 使用真实 MySQL 验证 V24 清理字典数据类型孤儿并增加强引用。 */
@Testcontainers
class DictDataTypeMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v24_repairsInvalidReferencesAndAddsRestrictForeignKey() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("23")).migrate().migrationsExecuted)
                .isEqualTo(22);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        insertType(jdbcTemplate, 9_084_001L, "v24_active", false);
        insertType(jdbcTemplate, 9_084_002L, "v24_deleted", true);
        insertData(jdbcTemplate, 9_084_011L, "v24_active", false);
        insertData(jdbcTemplate, 9_084_012L, "v24_missing", false);
        insertData(jdbcTemplate, 9_084_013L, "v24_missing", true);
        insertData(jdbcTemplate, 9_084_014L, "v24_deleted", false);

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("24")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(deleted(jdbcTemplate, 9_084_011L)).isFalse();
        assertThat(count(jdbcTemplate, 9_084_012L)).isZero();
        assertThat(count(jdbcTemplate, 9_084_013L)).isZero();
        assertThat(deleted(jdbcTemplate, 9_084_014L)).isTrue();
        assertThat(typeCount(jdbcTemplate, "system_menu_type")).isEqualTo(1);
        assertThat(typeCount(jdbcTemplate, "system_data_scope")).isEqualTo(1);
        assertThat(activeDataCount(jdbcTemplate, "system_menu_type")).isEqualTo(3);
        assertThat(activeDataCount(jdbcTemplate, "system_data_scope")).isEqualTo(5);
        assertThat(constraintCount(jdbcTemplate, "fk_dict_data_type", "FOREIGN KEY"))
                .isEqualTo(1);
        assertThat(indexCount(jdbcTemplate, "idx_dict_type")).isEqualTo(1);

        assertThatThrownBy(() -> insertData(jdbcTemplate, 9_084_015L, "v24_missing", false))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_dict_data_type");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM system_dict_type WHERE id = ?", 9_084_001L))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_dict_data_type");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE system_dict_type SET type = ? WHERE id = ?", "v24_renamed", 9_084_001L))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_dict_data_type");
    }

    private static void insertType(JdbcTemplate jdbcTemplate, long id, String type, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO system_dict_type (id, name, type, status, deleted) VALUES (?, ?, ?, 0, ?)",
                id,
                "migration-" + id,
                type,
                deleted);
    }

    private static void insertData(JdbcTemplate jdbcTemplate, long id, String type, boolean deleted) {
        jdbcTemplate.update(
                """
                INSERT INTO system_dict_data (id, sort, label, value, dict_type, status, deleted)
                VALUES (?, 1, ?, ?, ?, 0, ?)
                """,
                id,
                "migration-" + id,
                "value-" + id,
                type,
                deleted);
    }

    private static int count(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_dict_data WHERE id = ?", Integer.class, id);
    }

    private static boolean deleted(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject("SELECT deleted + 0 FROM system_dict_data WHERE id = ?", Boolean.class, id);
    }

    private static int typeCount(JdbcTemplate jdbcTemplate, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_dict_type WHERE type = ? AND deleted = b'0'", Integer.class, type);
    }

    private static int activeDataCount(JdbcTemplate jdbcTemplate, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_dict_data WHERE dict_type = ? AND deleted = b'0'", Integer.class, type);
    }

    private static int constraintCount(JdbcTemplate jdbcTemplate, String name, String type) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'system_dict_data'
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
                  AND table_name = 'system_dict_data'
                  AND index_name = ?
                  AND column_name = 'dict_type'
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
