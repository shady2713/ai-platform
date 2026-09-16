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

/** 使用真实 MySQL 验证 V22 修复菜单父级孤儿、非法类型和循环，并拒绝负数父级。 */
@Testcontainers
class MenuParentMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v22_repairsInvalidHierarchyAndRejectsNegativeParentIds() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("21")).migrate().migrationsExecuted)
                .isEqualTo(20);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        insertMenu(jdbcTemplate, 9_079_001L, 9_079_099L, 1, false);
        insertMenu(jdbcTemplate, 9_079_002L, 9_079_001L, 2, false);
        insertMenu(jdbcTemplate, 9_079_003L, 9_079_004L, 1, false);
        insertMenu(jdbcTemplate, 9_079_004L, 9_079_003L, 2, false);
        insertMenu(jdbcTemplate, 9_079_005L, 9_079_005L, 1, false);
        insertMenu(jdbcTemplate, 9_079_006L, 0L, 1, true);
        insertMenu(jdbcTemplate, 9_079_007L, 9_079_006L, 2, false);
        insertMenu(jdbcTemplate, 9_079_008L, 0L, 3, false);
        insertMenu(jdbcTemplate, 9_079_009L, 9_079_008L, 2, false);

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("22")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(parentId(jdbcTemplate, 9_079_001L)).isZero();
        assertThat(parentId(jdbcTemplate, 9_079_002L)).isEqualTo(9_079_001L);
        assertThat(parentId(jdbcTemplate, 9_079_003L)).isZero();
        assertThat(parentId(jdbcTemplate, 9_079_004L)).isZero();
        assertThat(parentId(jdbcTemplate, 9_079_005L)).isZero();
        assertThat(parentId(jdbcTemplate, 9_079_007L)).isZero();
        assertThat(parentId(jdbcTemplate, 9_079_008L)).isZero();
        assertThat(parentId(jdbcTemplate, 9_079_009L)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.table_constraints
                        WHERE constraint_schema = DATABASE()
                          AND table_name = 'system_menu'
                          AND constraint_name = 'chk_menu_parent_id_non_negative'
                          AND constraint_type = 'CHECK'
                        """,
                        Integer.class))
                .isEqualTo(1);
        assertInvalidParentRejected(jdbcTemplate, 9_079_002L, -1L);
    }

    private static void insertMenu(JdbcTemplate jdbcTemplate, long id, long parentId, int type, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO system_menu (id, name, type, parent_id, deleted) VALUES (?, ?, ?, ?, ?)",
                id,
                "migration-" + id,
                type,
                parentId,
                deleted);
    }

    private static long parentId(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject("SELECT parent_id FROM system_menu WHERE id = ?", Long.class, id);
    }

    private static void assertInvalidParentRejected(JdbcTemplate jdbcTemplate, long id, long parentId) {
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE system_menu SET parent_id = ? WHERE id = ?", parentId, id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_menu_parent_id_non_negative");
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        return flywayConfiguration(dataSource).target(target).load();
    }

    private static FluentConfiguration flywayConfiguration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}
